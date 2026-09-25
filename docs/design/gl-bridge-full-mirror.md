# GL bridge 全量镜像

## 背景与动机

RT（渲染线程分离）模式下，`RenderThreadRedirector` 把游戏/模组字节码里
`INVOKESTATIC org/lwjgl/opengl/X.m` 改写到 `bridge/opengl/X.m`，前提是该方法存在于
镜像表（= bridge 类字节码中声明的方法集）。未镜像的调用保持原 owner 直通——在无
GL context 的调用线程执行真实 GL，轻则告警刷屏，重则崩溃
（`No OpenGL context found in the current thread`，BoxUtil 实机已发生）。

历史策略是「炸了再补」的按需镜像：覆盖面 ~600 方法，而游戏 lwjgl.jar 的
`org/lwjgl/opengl` 包共有 **220 个类、3762 个 public static 方法**。BoxUtil 一次实机
就产生 187 条未镜像告警（`GL44.glClearTexImage`、`GL41.glProgramUniform*`、
`GL40.glGenTransformFeedbacks`、`NVBindlessTexture.glUniformHandleuNV` 等）。

本文档定义**一次性全量镜像**方案：覆盖 jar 内全部 GL 调用类的全部 public static
方法，辅以覆盖率测试保证永不漏镜像。

## 方案总览

1. **静态代码生成器** `tools/gen_gl_bridge.py`：用 `javap` 解析游戏 `lwjgl.jar`，
   为每个目标类生成 bridge 镜像源码，提交进仓库（一次性生成物，不搞运行期动态
   生成；LWJGL 版本变动时手动重新生成）。
2. **生成形态**：
   - 已有手写 bridge 的 24 个 GL 调用类：生成基类 `bridge/opengl/<Name>Gen.java`，
     手写类 `extends <Name>Gen`。`invokestatic bridge/opengl/<Name>.m` 的方法解析
     先命中手写类自身方法（精心语义：顶点流/状态仿真/id stash/map 仿真），未覆盖
     的落到 Gen 基类的通用语义——**手写永远优先，生成物只兜底**。
   - 无手写对应的其余 GL 调用类：直接生成 `bridge/opengl/<Name>.java`。
3. **镜像表超类遍历**：`RenderThreadRedirector.buildMirrorTable()` 沿 `superName`
   链（限 bridge 包内）递归收集方法，Gen 基类方法自动入表。
4. **覆盖率测试**（sso-app）：ASM 解析真实 lwjgl 类（含其 `org.lwjgl.opengl` 内
   超类链）的 public static 方法全集，断言 ⊆ bridge 类（含 Gen 超类链）方法集。
   白名单为空——缺什么补什么，不允许豁免。

## 生成分类规则

按「返回类型 × 参数形态 × 方法名」分四类通道（与手写惯例完全同构）：

| 类别 | 判定 | 生成体 |
|---|---|---|
| 命令型 | `void` + 无 buffer 参数 | `BridgeSupport.enqueue(() -> real.glX(a, b))`；`CharSequence` 直接捕获（不可变），`CharSequence[]` 先 `clone()` |
| 快照命令型 | `void` + 恰好 1 个 buffer 参数且非 getter/gen | `BridgeSupport.enqueueSnapshot(buf, s -> real.glX(..., s.asXxxBuffer()))` |
| 阻塞直通型 | `void` + (多 buffer / 含 PointerBuffer·PointerWrapper / 名字 `^gl(Get|Are)\w*`) | `BridgeSupport.blockingWait(() -> real.glX(...))`，调用方阻塞期间 buffer 直传安全 |
| 阻塞资源型 | `void` + 名字 `^glGen\w*` 带 buffer；或非 void 返回且名字 `^gl(Gen|Create|New)\w*` | `blockingWaitResource` / `blockingGetResource`（不计 StallDetector） |
| 阻塞取值型 | 其余非 void 返回 | `BridgeSupport.blockingGet(() -> real.glX(...))`，buffer 参数直传 |

细化规则：

- **固定下限 16 的 glGet 缓冲族**（`glGetInteger/glGetFloat/glGetDouble/glGetBoolean`
  及其索引变体、`glGetInteger64` 族）：单 buffer 时经 `GetBufferFill.fillXxx(params,
  16, buf -> real...)` 绕过 LWJGL2 固定 `remaining >= 16` 检查（小 buffer 惯用法
  实机炸过）。`GetBufferFill` 需补 `fillDoubles`。其余单 buffer `glGet*`
  （glGetTexImage/glGetBufferSubData 等按参数计算下限的）直通即可——调用方 buffer
  不满足时原版同样炸，非 bridge 责任。
- **GLSync 签名**（仅 GL32/ARBSync/ARBCLEvent）：不生成。GL32/ARBSync 手写已覆盖；
  `ARBCLEvent.glCreateSyncFromCLeventARB`（返回 GLSync）手写补齐（照抄
  GL32.glFenceSync 的包装惯例）。
- **回调注册**（`glDebugMessageCallback` 等，参数为 `KHRDebugCallback`/
  `AMDDebugOutputCallback`/`ARBDebugOutputCallback`）：enqueue 捕获引用，回调随后在
  渲染线程触发（语义等价于单线程的调用方线程——渲染线程即唯一的 GL 线程）。
- **map 族**（`glMapBuffer*/glUnmapBuffer*/glMapTexture2DINTEL/`ATIMapObjectBuffer 等）：
  生成物走 blockingGet 直通（语义正确，无仿真优化）；GL15/GL30/
  ARBVertexBufferObject 的手写仿真实现经 override 优先命中，热点路径不受影响。
- **LongBuffer 单 buffer 命令**：`enqueueSnapshot` 无 LongBuffer 重载——生成器遇到
  时降级 blockingWait 直通并在生成日志中记录（实际清单中不存在此类）。

## 目标类清单

生成集 = jar 内 `org/lwjgl/opengl` 含 public static 方法的 220 类 − 16 个
窗口/平台/上下文基建类（`Display GLContext Util AWTUtil ContextGL ContextGLES
LinuxEvent LinuxKeycodes MacOSXNativeMouse NVPresentVideoUtil NVVideoCaptureUtil
Pbuffer Sync WindowsDisplay WindowsKeycodes XRandR`）= **204 类**。

- 基建类中 `Display/GLContext/Util` 维持既有手写镜像（`Drawable/SharedDrawable/GLSync`
  为类型 remap，无静态方法面）；其余不镜像——它们只在启动期主线程窗口创建路径使用，
  不属于模组渲染调用面。
- `MIRRORED_CLASS_NAMES` 扩展为 204 + 既有手写 6（Display/GLContext/Drawable/
  SharedDrawable/GLSync/Util）= 210 个名字。
- 真实类的 `org.lwjgl.opengl` 内超类链（如 `ARBVertexBufferObject → ARBBufferObject`、
  `NVVertexProgram → ARBVertexProgram`）由生成器遍历合并——javac 记 owner 为编译期
  引用类，继承来的方法也必须出现在对应 Gen 中。

## 风险与对策

- **爆炸半径**：改写面从 ~600 方法扩到 ~3700。所有生成体只经既有四条通道
  （enqueue/enqueueSnapshot/blockingWait/blockingGet），语义与手写版同构；
  每条通道已有充分实机验证。
- **仿真情一致性**：`SimulatedGlState` 簿记只由手写 setter 维护；生成物不会绕过
  簿记（同名同 desc 调用恒解析到手写类）。未簿记状态的 getter 走阻塞通道，
  永远返回真值。
- **aux 原生线程**：`BridgeSupport.enqueue/blockingGet` 的 auxNative 分支对生成物
  同样生效（共享上下文线程内联直执），无需特判。
- **field 访问告警**（`visitFieldInsn` 分支）是 javac 常量内联之外的少量噪音，
  设计使然，不在本方案消除范围内。

## 验证

1. `./gradlew build -x deployMod`：全量编译 + 单测（含新增覆盖率测试）。
2. 冒烟：`./tools/smoke_test_game_launch.sh /mnt/store/Games/Starsector098-linux
   300 game`，日志中「GL 调用未镜像」WARN 应清零（field 访问类除外）。
3. 生成器可重复性：重新运行生成器产出与仓库内提交物 diff 为空。

## 再生成流程

LWJGL 版本升级（游戏换 jar）时：

```bash
python3 tools/gen_gl_bridge.py /mnt/store/Games/Starsector098-linux/lwjgl.jar
```

生成器幂等覆写 `modules/internal/sso-render/src/main/java/github/kasuminova/
ssoptimizer/bridge/opengl/` 下的 `*Gen.java` 与纯生成类；手写类不触碰（ extends
声明除外，由生成器报告需要手动跟进的类）。
