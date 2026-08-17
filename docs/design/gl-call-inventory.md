# GL 调用盘点：bridge 覆盖面决策

前置文档：`render-logic-separation-entrypoints.md`（首轮切入点方案）。
本文档汇总游戏本体（named 反编译源码，调查副本 /tmp/ss_deob）、SSOptimizer 自身、
常用模组（GraphicsLib、BoxUtil）的 GL 调用面，据此划定 bridge 初版必须覆盖与可推迟的子集。

## 游戏本体：纯固定管线

调查结论：零 shader、零 GL15/GL30 核心调用，全部渲染走 GL11 固定管线。

| 调用面 | 规模/位置 | bridge 决策 |
|---|---|---|
| GL11 immediate 顶点流 | glVertex2f×1252、glBegin/glEnd×527、glTexCoord2f×476、glColor4ub 等 | **必须覆盖**，且是 immediate 顶点拦截的首要对象 |
| GL11 状态/矩阵 | glEnable×359、glPush/PopMatrix×396、glBlendFunc×308、glOrtho/glViewport/glClear 等 | **必须覆盖**，矩阵操作后续改走 CPU 仿真栈 |
| GL14 glBlendEquation | ×18 | **必须覆盖**（签名镜像即可） |
| ARBVertexBufferObject 5 件套 | 仅 com.fs.graphics.SpriteBatch | **必须覆盖** |
| EXTFramebufferObject | 仅 com.fs.graphics.FrameBufferObject | **必须覆盖**（FBO 三路径之一） |
| Display 全生命周期 | create/update/isCloseRequested/尺寸查询等 | **必须覆盖**（update/create 是帧同步与上下文迁移的支点） |
| display list 5 方法 | GLListManager/BitmapFontRenderer 等低频路径 | **可推迟**（按需做命令帧重放，FR 的 ListManager 同思路） |

## SSOptimizer 自身

| 调用面 | 位置 | 备注 |
|---|---|---|
| GL11/GL14 | 各渲染助手 | 与游戏同面 |
| GL15 全套 | DynamicVbo（环形 VBO） | bridge 需含 GL15 buffer 5 件套 |
| GL30 | DynamicVbo / 部分助手 | 初版可直通录制，后续评估 |
| EXTFramebufferObject | RadarCompositeCache | FBO 三路径之一 |
| glReadPixels | FramebufferCapture（基准截图） | getter 回读，走阻塞 get 通道；截图工具链后续迁移到渲染线程 |

自身代码可以在接入期直接改为「主线程收集、渲染线程提交命令」，不完全依赖字节码改写——这是与游戏/模组路径的差异优势。

## GraphicsLib

| 调用面 | 规模 | bridge 决策 |
|---|---|---|
| GL11 | 常规固定管线 | 覆盖 |
| GL13 glActiveTexture | ×17 | **必须覆盖**（多纹理单元是 shader 前置） |
| GL20 shader 全族 | glUseProgram/glUniform*/glGetUniformLocation/编译链接回读 | **可推迟到二期**（初版白名单阶段可声明不兼容或直通串行化） |
| FBO 三路径 | GL30/ARB/EXT 运行时选择 | **必须覆盖**三条路径 |

主渲染链路零 getter 回读——好消息，瓶颈只在编译期回读（见 getter 清单）。

## BoxUtil（初版声明不兼容，留钩子）

调用面横跨 GL11~GL44 高端面：VAO、glMapBufferRange、compute（glDispatchCompute、
glMemoryBarrier）、glBufferStorage、instanced、bindless（NV/ARB 全套）、
GL32 glFenceSync/glWaitSync/glDeleteSync。

线程模型（桥接设计的输入）：

- ShaderCore.init 在主线程创建 3 个 SharedDrawable 后台线程（渲染/逻辑/逻辑辅助）；
- 各线程 Drawable.makeCurrent + glGetError 校验，持有各自 GL 上下文；
- CPU 协调用 Phaser，GPU 命令流可见性协调用 GLSync fence（5 个 fence 交接点）。

对应我们已留的结构钩子：`RenderQueue.submit` 多生产者通道（aux-context 入队）、
`FrameFence`/`SignalFenceCommand`/`WaitFenceCommand`（fence 信号可来自渲染流或
CPU 侧生产者线程，glWaitSync 乱序录制不死锁）。

## bridge 初版覆盖面结论

**必须覆盖**：

- GL11 固定管线全集（状态/矩阵/immediate 顶点流）
- GL14 glBlendEquation
- GL15 buffer 5 件套（glGenBuffers/glBindBuffer/glBufferData/glBufferSubData/glDeleteBuffers）
- ARBVertexBufferObject 5 件套（ARB 版同语义）
- FBO 三路径（GL30 / ARB / EXT FramebufferObject）
- GL13 glActiveTexture
- Display / GLContext 核心（create/update/isCloseRequested/尺寸、makeCurrent 语义）

**可推迟**：

- GL20 shader 全族（二期，GraphicsLib 专项）
- GL30+ 高端面 / compute / bindless（BoxUtil 专项，初版声明不兼容）
- display list 5 方法（低频，按需命令帧重放）
- 低频 getter（逐个按仿真覆盖推进）

## getter 特殊处理清单

| getter | 实际用途 | 处理策略 |
|---|---|---|
| glGetString(GL_VERSION/GL_EXTENSIONS) | 启动期能力探测（一次性） | **状态仿真**：渲染线程创建后一次性取回并缓存，主线程读缓存 |
| glGetInteger（显存/MSAA/矩阵等） | 启动期配置探测 + 少量运行期查询 | 启动期 pname **走仿真缓存**；未覆盖 pname 走阻塞 get（StallDetector 兜底暴露滥用者） |
| glReadPixels | FramebufferCapture 截图（低频、帧尾） | **阻塞 get**：语义强依赖执行完成，不值得仿真 |
| shader 编译/链接回读（glGetShader/glGetProgram/glGetShaderInfoLog） | GraphicsLib 编译期校验（加载期一次性） | **阻塞 get**：加载期一次性 drain 可接受；二期随 shader 族一起评估 |
| glGetError | BoxUtil 等模组的健康校验 | **阻塞 get**（低频）；若 profiler 显示高频再考虑仿真 |
