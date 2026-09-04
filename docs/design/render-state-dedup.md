# 状态命令去重（render-state-dedup）

## 背景与目标

v36 profile（主线程 wall 采样）显示录制侧状态变换 10.9% + 纹理绑定 9.5% +
矩阵 4.2%，合计 24.6%。目标：录制侧对**连续相同**的高频状态命令
（glBindTexture / glEnable / glDisable / glBlendFunc 等）只入队一次，渲染线程
不再重复执行冗余的状态设置。

## 无副作用审计（前置硬要求）

去重的前提：录制侧状态跟踪与真实 GL 状态一致，即「跳过一条状态命令」时真实
GL 状态必须仍等于该命令的目标值。审计枚举了所有绕过录制状态机直接执行真实
GL 的旁路，结论如下：

### 1. 已确认的纯录制段（dedup 安全）

- **主线程与 aux-context 生产者线程的状态命令**：全部经 ASM 重定向
  （`RenderThreadRedirector`，owner 改写表覆盖 `org/lwjgl/opengl/{GL11..GL44,
  ARB*, EXT*, Display, GLContext, ...}`）进入 bridge 镜像，录制为帧命令。
- **mod renderHook（BoxUtil/GraphicLib 等）**：其字节码同样经重定向器改写，
  GL 调用一律入队；BoxUtil 的 aux-context 结构经 `SharedDrawable` 解折叠为
  真实共享 GL 上下文（见其 javadoc）——makeCurrent 成功的模组线程标记为
  aux 原生线程，此后其 bridge GL 调用全部原生直执、不再入队，与主线程
  录制流完全隔离（修复折叠模型下双命令流指令级交错互污染导致的
  BoxUtil 管线贴图错乱）。主线程录制侧**不存在「在渲染线程
  直接调 LWJGL 的 mod」通路**——重定向覆盖所有经 LaunchClassLoader 加载的类，
  运行时「GL 调用未镜像」告警计数为 0（v44c 基准日志验证）。
- **native batch helper（ContrailBatchHelper / SpriteBatchNative /
  EngineBatchNative / BitmapFontRendererHelper 等 JNI 直调 GL）**：分离模式下
  `NativeRuntime.isGlReady()` 恒为 false（跳过 glad 函数指针加载），
  SpriteBatch / EngineBatch / SpriteRenderHelper / 字体 / TexturedStrip 的
  native 加速路径全部落 Java 回退，回退路径的 `org.lwjgl` 调用被重定向录制
  入队。**分离模式下不存在 native 直调旁路**。
- **FBO 切换、Display 级操作**：`bindFramebuffer` 系列在 bridge 内录制
  （录制侧跟踪 `framebufferBinding` + enqueue 命令）；`Display.update` 为
  「Display.update 命令 + swap」；`Display` 其余直通方法（getWidth/
  processMessages 等）只读 LWJGL 缓存字段，无 GL 状态副作用。
- **GluSupport / Util / GLContext**：`enqueueSphereDraw` 整段入队；`Util` 走
  阻塞 getter 通道；`GLContext.getCapabilities` 首次阻塞取回后缓存。均无旁路。

### 2. 已确认的旁路（dedup 边界，必须正确失效）

| 旁路 | 机制 | dedup 失效方式 |
|---|---|---|
| glCallList 显示列表 | display list 在渲染线程编译/执行（真实 GL），list 内任意状态改变绕过录制侧；执行 `glCallList` 消费调用时刻的 current 状态 | `glCallList` 是一条帧命令，任何命令插入都会使相邻性判据（commitSeq）失效，其后的状态命令照常入队 |
| 未镜像 GL 调用 | 调用点 owner 未进镜像表时保持原 owner，在调用线程直接执行真实 GL；bridge 无法感知 | 运行时计数为 0（v44c 验证）；若未来出现且发生在渲染线程，dedup 只影响「同参状态命令是否重放」的优化正确性边界（视觉可能错位），帧失败熔断语义不受影响——属已声明的不可感知边界 |
| 渲染线程命令体 | bridge 包类执行真实 GL（排除规则豁免重定向） | 命令体只执行「录制下来的命令」；被 dedup 跳过的命令不执行（真实状态未变），命令体不读写录制侧状态缓存，无一致性问题 |

### 3. 矩阵类不纳入 dedup（审计决定，已随流内矩阵指令演进）

`glLoadIdentity/glTranslatef/glRotatef/glScalef/glLoadMatrix/glMultMatrix`
不纳入：相邻重复率低（参数几乎必然不同）、浮点参数位模式指纹收益低，且演进
方向是主线程 CPU 仿真栈（见 GL11 类 javadoc 后续阶段计划）而非命令去重。
`glMatrixMode`（标量状态命令，切换当前矩阵栈）在纯命令路径下纳入。

后续演进（已落地）：矩阵命令族（glPushMatrix/glPopMatrix/glLoadIdentity/
glTranslatef/glRotatef/glScalef/glMatrixMode）默认编码进顶点流的流内指令
（开关 `-Dssoptimizer.render.streamMatrixOps`，VertexStream OP_PUSH_MATRIX
族）——挂起流内的矩阵指令经 `VertexStream.hasPendingStateOps()` 扩展语义
保守失效 dedup 相邻性（与流内 enable/bind 同判据）；开关关闭的回退路径下
glMatrixMode 仍走 enqueueState 保留去重。glTranslated/glOrtho/glLoadMatrix/
glMultMatrix（double 载荷与 buffer 快照低频路径）保持 enqueue 不变。

## 去重模型：commitSeq 相邻性判据

- `RenderFrame` 新增 `commitSeq`（volatile，`synchronized add` 内递增）：
  帧命令列表的单调提交序号，任何命令插入（主线程非状态命令、顶点流落帧、
  glCallList、aux 生产者线程并发提交）都会使其变化。
- 录制侧 `StateDedup`（随 `RecordingContext` 按线程隔离）跟踪「上一条已入队
  的状态命令」的类型 + 参数指纹（最多 4 个 int 槽，float 转位模式）+ 入队
  时刻的 commitSeq。
- 新状态命令仅当「类型与参数全部相同 **且** 自上次入队以来 commitSeq 未变」
  时被跳过；否则照常入队并更新指纹。
- 帧边界（swap）重置缓存：跨帧不延续去重（GL 状态虽跨帧保持，保守起见帧间
  状态命令照常入队，避免跨帧时序依赖）。

该模型无需感知具体旁路——commitSeq 判据天然覆盖一切「命令流插入 = 状态可能
已被改变」的边界（含审计清单中的 glCallList 与 aux 并发提交）。

## 实现与开关

- `StateDedup`（bridge/opengl）：指纹 + 相邻性判据。
- `BridgeSupport.enqueueState(type, a, b, c, d, command)`：去重入口；
  `RecordingContext.dedupFrame`（主线程 swap 时刷新，避免逐命令
  `queue().currentFrame()` 的同步开销）为相邻性判据来源。
- 开关 `-Dssoptimizer.render.statededup=false`（默认开，`BridgeSupport.
  stateDedupEnabled`），用于 A/B 对照。
- 纳入清单：glBindTexture / glEnable / glDisable / glBlendFunc / glAlphaFunc /
  glShadeModel / glLineWidth / glPointSize / glPolygonMode / glHint /
  glDepthMask / glDepthFunc / glCullFace / glFrontFace / glColorMask /
  glStencilFunc / glStencilOp / glStencilMask / glScissor / glViewport /
  glClearColor / glClearStencil / glPixelStorei / glMatrixMode。

## 测试

- `StateDedupTest`：相邻相同跳过、参数/类型不同不跳过、任何命令插入打断、
  invalidate 失效。
- `GL11BridgeTest`：连续相同去重、命令插入打断、aux 提交打断、顶点流落帧
  打断、开关关闭时逐条入队。
