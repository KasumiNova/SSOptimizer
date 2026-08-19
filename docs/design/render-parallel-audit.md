# 渲染并行录制：共享状态审计清单（阶段 0 门禁产物）

状态：**已汇总（2026-08-19）**，待用户过目后放行阶段 2。
审计方法：v4flash swarm 六域只读审计（反编译源码 `/tmp/sso-src-all` + 四模组反编译/反汇编）。
判定规则：只读游戏状态 = 可并行；任何写或读写混合（含惰性初始化缓存）= 必须串行或加守卫。

## 0. 总结论

- **Ship/Engine 渲染主链本体干净**：无静态渲染参数缓存、无跨实体容器写，层内实体分片并行可行。
- 全部硬串行阻力收敛到 **5 个共享点**（见 §1），其中真正需要前置改造的只有
  **GLListManager 全局静态**（含字体链）与 **contrailEngine 单例 Map**（每舰渲染都写）。
- **情报修正**：0.98a 无 `LazyFont`（设计文档旧情报有误）；字体绘制走
  `BitmapFontRenderer`（GL 显示列表 + 立即模式），其共享性同样收敛到 GLListManager。
- **getter 负载已被现有仿真全覆盖**（唯一条件可达缺口 = `GL_MAX_VIEWPORT_DIMS`），
  补 5 个静态能力缓存后并行段可实现零阻塞往返（§5）。
- **模组 hook 全部锚定成功**（§4）：GraphicsLib/MagicLib 经 `CombatLayeredRenderingPlugin`
  混入 `LayeredRenderer` 层遍历；BoxUtil 无游戏侧锚点（自建 GL 线程 + fence 协议），
  维持「初版声明不兼容」决策。

## 1. 必须串行清单（共享可变状态写，跨域合并）

| # | 共享点 | 关键调用点 | 依据 | 处置 |
|---|---|---|---|---|
| H1 | **GLListManager 全局静态**（`activeLists/allocatedLists/freeLists/currListId/buildingList/suspend`） | `Ship.java:3760-3773/3109-3122`（jitter）、`FfIndicator.java:270-275`、`BitmapFontRenderer.java:740-745`（字体显示列表惰性缓存）、`GLLauncher.java:996-998`（每帧 `nextFrame()` 清理）；`buildingList` 分支读 `Ship.java:3640/3685`、`BeamWeapon.java:371/394` | 并发 beginList/callList 互踩 ID 池与集合；`buildingList` 竞态进错分支 | **前置改造**：worker 私有列表池（各段独立 build，回放前主线程统一 merge 进 nextFrame 无效化流程）——改造后字体/jitter/FF 全部释放并行 |
| H2 | **contrailEngine 单例 Map**（`Map<EngineSlot, Group>` + 每 key LinkedList） | `Engine.java:873-875`（`Engine.render` 每舰/导弹 `addSegment`）、`Engine.java:82-198`（trail 生命周期）、`CombatEngine.java:1314`（advance 老化）；`CONTRAILS_LAYER` 渲染读（`CombatEngine.java:282`） | **每艘船渲染都写同一 HashMap**——不分区则舰船层无法并行 | **前置改造**：per-slot 段内局部缓冲 + 主线程按段序合并（或段锁，粒度 per-key） |
| H3 | **粒子组容器**（12 个 `DynamicParticleGroup` + `DebrisParticleSystem.groups[8]` + `ExplosionParticleSystem.groups[5]` + `CombatParticleEffects` 静态列表） | 写：`CombatEngine.java:2744-2876`（`addXxxParticle`）、`DebrisParticleSystem.java:151`、`ExplosionParticleSystem.java:202`；渲染：`CombatEngine.java:937-938/948/950-963` | 组内多实体并发写互踩；`ExplosionParticleSystem.random` 非线程安全；debris/explosion 含惰性初始化 | **分区粒度下限 = 组**：组整段串行；组渲染段（底层:937-938、顶层:948-963）钉为固定串行段 |
| H4 | **字体/UI 进程级共享**（除 H1 外）：共享 `BitmapFont` 的 `kerningLookupPair`（`BitmapFont.java:43-51`）、`Version.renderer` 静态单例（`Version.java:16`）、`ScissorStack` 静态栈（`ScissorStack.java:9-11`，`Panel.java:195-210` 全 HUD 子树 push/pop） | HUD 全链（`CombatState.java:910-984`） | 任何画文字的段都踩；HUD 整段不可直接并行 | **HUD 整段钉为固定串行段**（收益放实体层）；后续如需 HUD 并行再给 ScissorStack/kerning pair 加守卫 |
| H5 | **模组静态列表**（MagicLib `MagicRenderPlugin` 4 静态列表渲染期读写删除；GraphicsLib `renderForeground` 就地排序 `engine.getShips()` 并改写共享 SpriteAPI——`ShaderLib.java:1079-1080`） | 经分层插件混入层遍历（§4） | 模组渲染 hook 已在决策中钉为固定串行段 | 编排上锚定（§4），不改游戏代码 |

次要/条件项：
- `Profiler` 全局静态栈（`Ship.java` 多处 begin/end）——默认 `enabled=false` 空操作；**启用 profiling 时强制串行**（或改 ThreadLocal 栈）。
- 武器皮肤共享 Sprite（`Misc.getWeaponSkin` 缓存实例，`MissileWeapon.java:524-530/611-617`）——稀有路径，分区约束「同皮肤武器同段」或文档化。
- `SmoothParticle.java:20-23`/`NegativeParticle.java:24` 静态顶点缓冲——当前走立即模式分支无并发写，**潜伏性**：勿启用其批处理路径。
- `StaticParticleGroup.render` 惰性 FBO/displayList 初始化——本审计未发现实例化点，低优先。

## 2. 实体局部写清单（按实体分区即安全 → 分区约束「同实体同段」）

- Ship：`visible`/`combinedAlphaMult` 写后恢复（`Ship.java:2933/3388/3461-3496`）、实体私有 sprite 全链路
  （纹理对象共享但只读）、jitter 私有 Random（`JitterRenderer.java:30-33` 同实体同段即可）、
  shield 颜色写后恢复、renderSlotCovers 私有 sprite、copyLocation 二次渲染（要求该实体整体同段）。
- **H6 跨实体写（唯一）**：`Ship.java:2839/2776` renderShadow 内舰载机写其 `launchingShip`（母舰）
  的 sprite——分区约束「`isAnimatedLaunch && launchingShip != null` 的舰载机与母舰同段」。
- Engine：`engineStates` 惰性 put（每船一个实例）、glowSprite 实体私有。
- 弹道/光束：`BeamWeaponRay.hitGlow`、`BeamWeapon.glowSprite`、`BaseBeam.texturedLine`、
  `BallisticProjectile`/`Missile` 的 sprite/trail/jitter 均实例级 ✓。
- `DecalRenderer`：每船惰性建批量 SpriteBatch（`DecalRenderer.java:50-72`）——实例级但惰性初始化
  在渲染期，worker 内新建 SpriteBatch 的 GL 调用走该 worker 段录制 ✓（其构造 `glGetString(EXTENSIONS)`
  已缓存覆盖，§5）。

## 3. CombatEngine.render 调用序全景（锚点表主干）

帧序总编排在 `CombatState`（update 相 `:1150` → render 相 `:759` 起）：

| 序 | 调用点 | 内容 | 分段判定 |
|---|---|---|---|
| 1 | `CombatState:769` | `engine.renderBG`（背景/行星/星域，`:902` 活列表遍历） | 可并行（图层内部）或独立段 |
| 2 | `CombatState:781` → `CombatEngine:934` | 主战场渲染（见下） | 并行主战场 |
| 3 | `CombatState:783` | `CombatParticleEffects.renderParticles`（dev only） | 串行（H3） |
| 4 | `CombatState:787` | `combatMap.renderForeground` → **插件 renderInWorldCoords**（GraphicsLib PostProcess.pre 等） | **固定串行锚点** |
| 5 | `CombatState:791-792` | arcRenderer/waypointRenderer | 串行（与 4 并段） |
| 6 | `CombatState:913` | `combatMap.renderBackground` → **插件 renderInUICoords** | **固定串行锚点** |
| 7-10 | `CombatState:917-1007` | whiteout/warroom/消息/widgets/瞄准环/日辉/dimScreen/光标 | **固定串行（H4 字体/UI 共享）** |

`CombatEngine.render(boolean)`（`:934`）内部严格次序：
1. `:937-938` 底粒子组（H3）→ **固定串行段**
2. `:941-945` `renderer.renderExcluding` 按 `CombatEngineLayers` 枚举序遍历 20 层
   （`LayeredRenderer.java:28-42`，层内 ArrayList 插入序 for-each）→ **并行主区（按层→实体 range 两级切分）**
3. `:948` debrisSystem + `:950-963` 12 粒子系统（H3）→ **固定串行段**
4. `:964-966` `renderOnly`×3 尾层（ABOVE_PARTICLES/ABOVE_PARTICLES_LOWER/JUST_BELOW_WIDGETS）→ 同 3 并段
5. `:967-969` floatingTextManager（H4 字体链）→ 串行

## 4. 模组 hook 锚点表（用户决策 1 落地依据）

| 模组 | 入口机制 | 游戏侧调用点 | 渲染期行为 | 串行段锚定 |
|---|---|---|---|---|
| GraphicsLib | `ShaderHook`（EveryFrameCombatPlugin，settings.json plugins）+ `ShaderCombatLayerHook`（CombatLayeredRenderingPlugin，`EnumSet.allOf` 全层注册，按层分发 LightShader→ABOVE_SHIPS_AND_MISSILES、DistortionShader→JUST_BELOW_WIDGETS） | 分层段 `CombatEngine.java:941-966`；world 段 `CombatState:787`；UI 段 `CombatState:913`；advance `CombatEngine:1246` | LightShader 惰性整帧重渲进 foreground FBO + 全屏灯光复合；Distortion copyScreen 变形；PostProcess 跨段读上一帧缓冲 | 分层遍历含其 CustomCombatEntity 代理——**层内动态分流**（见 §6）；world/UI/advance 三段钉死 |
| MagicLib | `MagicRenderPlugin`/`MagicTrailPlugin`（BaseEveryFrameCombatPlugin → addLayeredRenderingPlugin，全层）+ MagicFakeBeamPlugin（renderInWorldCoords） | 同上 | 静态列表渲染期读写删；MagicTrailRenderer 直接 GL11 immediate | 同上 |
| LazyLib | **无渲染 hook**（工具库，javadoc 引用不算） | 无 | 无自有渲染状态 | 无需钉段 |
| BoxUtil | **自建后台 GL 线程体系**（SharedDrawable 共享上下文 + RenderingThread + LogicalThread×2），fence 同步点 `__SYNC_BEGIN_ADVANCE/__SYNC_FINISH_ADVANCE/__SYNC_AFTER_RENDERING_HOST` | 无游戏侧锚点 | SSBO 实例池 + shaderpack；跨线程 GL 资源共享 | **声明不兼容**（既有决策），保留 fence/aux-context 队列钩子 |

**关键结构事实**（审计 4+6 交叉验证）：`CombatLayeredRenderingPlugin` 经
`CombatEngine:855-859` 包成 `CustomCombatEntity` 注册进 renderer 层列表——模组渲染代码
**与原版实体混在同一 ArrayList，层内无类判别**。因此「层内实体分片」必须配合
**CustomCombatEntity 动态分流**：录制器在 renderable.render 调用点检测具体类，
命中模组实体即把该实体拉回串行段（层内顺序重排已由 FR 实证视觉可接受——
render-logic-separation-entrypoints.md 的分层归并结论）。

## 5. getter/同步调用盘点复核（审计域 6）

- 现有仿真（SimulatedGlState + bridge 缓存）已覆盖 render 段逐帧可达的全部 getter；
  游戏 + 四模组 render 段**仅剩 1 个条件可达缺口**：`FrameBufferObject.java:48`
  `glGetInteger(GL_MAX_VIEWPORT_DIMS)`（FBO 尺寸探测）。
- **必补（阶段 2 前置）**：5 个 context 生命周期常量的「首次阻塞取回 + 录制侧缓存」——
  `GL_MAX_VIEWPORT_DIMS(3386)`、`GL_MAX_TEXTURE_SIZE(3379)`、`GL_POINT_SIZE_RANGE(2834)`、
  `GL_STENCIL_BITS(3415)`、`GL_MAX_SAMPLES(36183)`（仿 glGetString 模式，零风险）。
- 同步族：glFlush/glFinish 已入队 no-op ✓；游戏与四模组 render 段 0 个 glMapBuffer/fence 调用 ✓。
- glGetFloat 单值重载/glGetBoolean 本次素材零调用点，不为并行补仿真（更广模组面另立审计）。

## 6. 分区方案初稿（阶段 2 输入）

**主切法：层内并行**（不按层切段——同一 ship 出现/影响多层，跨层切撞 H6 与实体局部写）。

1. **并行区**：`renderExcluding` 遍历内的**普通实体**（Ship 各体型层/ASTEROIDS/弹体/光束）——
   层内按实体索引 range 分给 N 个 worker 段；同实体同段；舰载机与母舰同段（H6）；
   同皮肤武器同段（稀有，文档化约束）。
2. **层内动态分流**：`CustomCombatEntity`（模组代理）+ `FfIndicator`（H1）命中即拉回当前串行锚点段。
3. **固定串行段**（按 §3 次序钉死）：底粒子组 →（并行区）→ debris/12 粒子系统/尾层 →
   浮动文字 → 插件 world 段 → arc/waypoint → 插件 UI 段 → HUD 全段。
4. **前置改造**（阶段 2 动工前完成，决定并行收益上限）：
   - **H1 GLListManager worker 私有化**（否则 jitter/字体/FF 全锁死，字体进而锁死 HUD 之外一切
     含文字段——舰船层内船名/状态文字同样走 BitmapFontRenderer，**这是并行区的隐形地雷，必须最先改**）
   - **H2 contrailEngine per-slot 缓冲 + 段序合并**（否则每舰一脚踩死舰船层并行）
   - §5 的 5 个静态能力缓存
5. **执行器**：复用 AiParallelExecutor 池（advance/render 不重叠）；worker 段任务失败走既有
   串行重跑降级（重录制无 GL 副作用）。
6. **开关**：`-Dssoptimizer.render.parallel=false` 整体回退。

**预期收益边界**（v50b 数据）：render 段 42% 中 Ship.render + encodeGroup + flushVertexStream +
粒子/尾迹 ≈ 60-70% 可并行；串行保留面 = 粒子组段 + HUD + 模组 hook（GraphicsLib ShaderHook 4,926
样本全局 pass）。并行录制把主线程 render 压到 ~15-18% 后，回放侧 VertexStream 消费
（回放 63.8% 忙）接棒成为瓶颈——即用户决策 2 的顺延项。

## 7. 门禁确认项（用户过目点）

1. 分区方案 §6 是否认可（尤其：层内重排可接受、CustomCombatEntity 动态分流策略）；
2. H1/H2 两项前置改造的工作量接受度（GLListManager worker 私有化涉及显示列表生命周期
   与 `GLLauncher.nextFrame()` 无效化流程的跨段协调）；
3. BoxUtil 维持不兼容声明；
4. 5 个静态能力缓存补齐纳入阶段 2 前置。
