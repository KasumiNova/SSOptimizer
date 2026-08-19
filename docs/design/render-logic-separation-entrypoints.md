# 渲染/逻辑线程分离：首轮切入点方案（第二轮调查）

前置文档：`render-logic-separation-feasibility.md`（首轮可行性结论：直接双线程不可行，
硬阻塞 5 项）。本文档在参考项目 **starsector-render（Fast Rendering, FR v0.8.4，
`/home/hikari_nova/IdeaProjects/starsector-render`）** 调研基础上给出首轮可落地方案。

## 关键认知修正：FR 不是「双线程并行」，而是「GL 执行迁移」

FR 的架构与「逻辑线程/渲染线程各自推进」有本质区别：

- **主线程**：原版主循环不变——advance、实体增删、输入、**render 遍历全部留在主线程**；
  唯一变化是所有 GL 调用被改写成**命令录制**（塞入当前帧命令队列）。
- **FR-Render 线程**：唯一持有 GL 上下文的线程，只执行录制的命令（含 `Display.update` 本身）；
  `Display.create` 被延迟到渲染线程执行，**主线程从头到尾没有 GL context**。
- **同步**：双缓冲命令帧 + `swapFramesAndSync`——提交第 N 帧后只等第 N-1 帧的 Future，
  形成一帧流水线重叠。

由此首轮调查的 5 项硬阻塞**全部不发生**：游戏状态读写仍在单线程（主线程），渲染线程
只碰快照化的命令数据，不遍历实体容器、不读游戏对象。render 内的状态写（Ship.render
的 visible/location 写后恢复等）照旧在主线程执行，因为 GL 已被延迟，不存在竞争。

## FR 的关键机制（可直接借鉴清单）

| 机制 | FR 实现 | 我们的对应资产 |
|---|---|---|
| GL 调用接管 | javaagent 常量池改写：GL11~GL44/Display/GLContext → bridge 类（游戏类与模组类两套改写表） | **NanoForge ASM 常量池/指令改写**，严格更强（无需 proxy 占位类/Krakatau 补丁那套 obf 攻坚工程） |
| 命令队列 | `Executor/Frame/Pool` 双缓冲 + `swapFramesAndSync`（等上一帧 Future） | 可整体照抄骨架（约 200 行级） |
| immediate mode | `VertexInterceptor`：glVertex 按 CPU modelview 当场变换存 scratchpad，glEnd 转 `glDrawArrays` | 我们已有 native 顶点烘焙（SpriteBatch MVP 烘焙），可直接落 native 批量缓冲 |
| 矩阵栈 | `TransformManager + MatrixStack` 纯 CPU 模拟 MODELVIEW | 已有 MVP 合成逻辑可迁移；主线程无 context 后矩阵读取**必须**来自仿真栈 |
| GL getter | 主线程侧状态仿真（AttribTracker/TextureTracker/ShaderTracker），未知 pname 才阻塞取回 | 照搬；这是「getter 打穿管线」的唯一解法 |
| 资源 id | `ResourceGenerator` 预生成 stash（主线程 pop，空才阻塞） | 照搬；直接解「advance 内 Ship.init 分配 VBO」硬阻塞 |
| buffer 参数 | `BufferPool` 录制时深拷贝（2 的幂分级池） | 照搬 |
| 显示列表 | `ListManager` 命令帧重放 | 按需（原版 GLListManager 路径） |
| 熔断 | `StallDetector`：60 帧窗口 stall ≥30 次抛异常 | 照搬，防模组把管线打死锁 |
| 分层归并 | `commitLayer` 按 (mode, texture, blend) 分组批量绘制 | 与我们段内归并量化方向一致，FR 实证了层内重排在视觉上是可接受的 |
| 帧尾 vsync | sleep 粗调 + 自旋精调的微秒级同步 | 照搬 |

## 模组兼容性风险（FR 已知问题对我们的预警）

用户已知 FR 与 BoxUtil 有兼容问题。FR 代码中的对应痕迹与根因：

1. **模组自建线程/上下文**（BoxUtil 的 SharedDrawable + glFenceSync）：FR 靠
   `ContextManager.createAuxContext` + fence 桥（glWaitSync 入队带 fence 引用防乱序死锁）+
   `execMutex` 全局串行化硬救回来的，属于高成本适配。我们初版应**声明不支持**并留好
   fence/aux-context 的队列钩子。
2. **模组每帧读 GL 状态**：每个未仿真 pname 都是一次全管线 drain，FR 靠不断扩充仿真覆盖
   面解决（changelog 里多次事故）。我们的状态仿真必须先盘点游戏+常用模组实际查询的 pname 集合。
3. **glFlush/glFinish 即时语义**：FR 抹成 no-op（语义在帧同步点统一保证）。
4. **VBO + 显示列表组合**：FR 用 `glGetString(GL_EXTENSIONS)` 剥掉 ARB_vertex_buffer_object
   骗原版走非 VBO 路径。我们已有 DynamicVBO 体系，走「全接管」路线，不需要这招。
5. **绕开 LWJGL 静态入口的模组**（反射/自绑 native）：任何方案都管不了，声明为不支持项。

## 首轮切入点方案（最小可行切法）

**目标**：FR 式骨架落地——GL 执行收拢到渲染线程，主线程逻辑+录制，一帧流水线重叠。
**明确不做**：真·render/advance 读并行（状态快照双缓冲）——FR 也没做，是后续轮次的事。

按依赖序分四步，每步独立可验证：

1. **GL 调用盘点与 bridge 骨架**
   - 盘点 named 源码 + GraphicsLib 实际调用的 GL11/GL15/GL30 方法子集（grep 全量）；
   - bridge 类（org.lwjgl.opengl.GL11 等签名镜像）+ `Executor/Frame/Pool` 双缓冲队列
     + `swapFramesAndSync`；帧尾用 Mixin 把 `Display.update` 调用点替换为 `Sync.syncAndUpdate`。
2. **ASM 重定向**
   - NanoForge ASM 改写 INVOKESTATIC/GETSTATIC owner：`org/lwjgl/opengl/GL11` → bridge GL11、
     `Display` → bridge Display；游戏类与模组类统一走 LaunchClassLoader 覆盖。
   - `Display.create` 延迟到渲染线程执行（`exec.wait` 模式），主线程零 context。
3. **主线程侧状态仿真 + immediate 顶点拦截**
   - CPU 矩阵栈（MODELVIEW 全操作）+ 投影；immediate 顶点当场变换入批量缓冲，glEnd 转 draw 命令；
   - getter 仿真（按第 1 步盘点结果覆盖）；资源 id 预生成 stash；BufferPool 快照。
4. **既有优化资产迁移**
   - SpriteBatch：收集端改读仿真状态（矩阵/stencil/scissor/FBO 全部来自仿真，零 GL），
     flush 从「直接 native GL」改为「提交 run 命令」，渲染线程执行 native 绘制——
     收集/flush 分离的既有架构天然契合，这是我们对 FR 的差异化优势；
   - EngineBatch/Shield/Contrail/字体等 native 直通路径同样改为「主线程收集、渲染线程执行」。

**验证标准**：gl_benchmark 120s 跑通无 CME、截图正确；主线程渲染段耗时（CombatEngine.render
46.2%）大部分转移到渲染线程；帧耗时 ≈ max(逻辑, 渲染执行) + 同步开销，理论上限约 1.5~1.8x，
实际预期受录制开销与驱动时间影响打折。

**首轮后的路线**：容器快照视图（ObjectRepository/LayeredRenderer 不可变快照）→
render 读并行 → 真·双线程。FR 未覆盖这部分，无可抄方案，需自行设计。

## 风险清单

- **录制开销必须显著低于直接 GL**：FR 已实证可行（命令批量化 + 层归并），但我们的
  native 单次 JNI 路径已很便宜，录制化后需基准对比确认无回退。
- **模组 GL 覆盖面**：ASM 重定向必须覆盖模组类（GraphicsLib 重度使用 shader/FBO）；
  漏接管 = 主线程无 context 直接崩。初版可先白名单验证 GraphicsLib/LazyLib 等常用模组。
- **基准/截图工具链**：我们的自动化截图与 profiler 读像素路径需迁移到渲染线程。
- **BoxUtil 级模组**：初版声明不兼容，留 fence/aux-context 队列钩子。
