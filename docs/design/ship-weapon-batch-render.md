# 舰船 / 武器 Sprite 合批渲染方案（规划）

> 状态：规划稿（未实现）。依据：/tmp/ss_deob 反编译源码调查（2026-08-16）。
> 关联文档：combat-render-logic-optimization.md（引擎合批为本方案的实现模板）。

## 1. 现状与机会

舰船/武器渲染是**每帧立即模式重绘**（主路径不走 display list）：

- `Ship.render`（Ship.java:3359 起）：每帧 `glPushMatrix + glTranslatef(船位置)`，
  船体 `sprite.setAngle(facing-90) + sprite.renderAtCenter(0,0)`（Ship.java:3512-3522），
  武器经 `ShipSlotEntity.render/renderUnder` 逐把绘制（Ship.java:3663-3671）。
- `Sprite.render`（Sprite.java:153-194）：每次 = `texture.bind()`（**裸 glBindTexture，
  无缓存**，TextureObject.java:65-67）+ pushMatrix/rotate + `glBegin(GL_QUADS)` 4 顶点 +
  glEnd + 弹栈。即每个 sprite 约 2 次矩阵栈 + 1 次纹理切换 + 1 组立即模式 JNI。
- 每把武器 3-4 个 sprite（under/barrel/charge/glow，PlasmaWeapon.java:88-91），
  一艘满编战舰每帧轻松 10+ 次上述开销；大规模战斗全场舰船 + 战机（战机同路径，
  就是普通 Ship 实例）每帧数千次 glBegin/glEnd。

**天然合批机会**：`TextureManager` 按路径缓存 `TextureObject`——同型号舰船共享船体纹理、
同 id 武器共享炮管纹理（weapon skin 覆盖也仅是换另一个共享纹理，Misc.java:6400-6438）。
按「纹理 × blend」分组即可把同纹理 quad 合并为一次 drawcall。

**现成范本**：损伤 decal 已用 `SpriteBatch`（VBO 批量，DecalRenderer.java:155-226）；
引擎合批的「收集 → CPU 展开 → 环形 VBO → glDrawElements」管线可直接复用
（DynamicVbo / EngineInstanceCollector 模式）。

## 2. 约束（调查得出的硬边界）

1. **display list**：jitter 的模块/护盾副本会把 sprite 渲染录进 list 跨帧重放
   （Ship.java:3101/3754/3797；GLListManager 每帧回收未用列表）。
   → 命中 `GLListManager.buildingList` 必须退回原版立即模式（与引擎合批同一策略）。
2. **stencil 区域**：d-hull 涂装（Ship.java:3527-3564）与损伤 decal 用 stencil 剪裁；
   `clipToBounds` 也可能动用剪裁。→ stencil/scissor 测试开启期间不收集、直接透传
   （或先 flush 再透传），避免批次跨越 stencil 状态边界。
3. **层边界 = 模组钩点**：GraphicsLib `ShaderCombatLayerHook` 挂在**全部**
   CombatEngineLayers 上，可在任意层边界插入自己的 shader/FBO/混合状态。
   → 合批的 flush 边界必须收缩在**单层内部**：层结束（或层间切换）时无条件 flush，
   绝不跨层携带未绘制的顶点。
   已实证原版渲染为 **layer-major**（LayeredRenderer.java:35-41：外层按层枚举序、
   内层按注册序逐实体 `render(layer, viewport)`，一艘舰的完整内部序列在一次调用内
   完成），因此按层收集、层末 flush 天然可行。
4. **层内 painter's algorithm**：船体/武器为普通 alpha（770/771），叠放次序敏感；
   原版层内顺序是 舰A(船体→武器) → 舰B(船体→武器)。按「纹理×blend」分组重排
   flush 会改变不同贴图舰船重叠处的遮挡关系。两种策略由开关切换：
   - 严格保序（默认）：仅连续同组 quad 并批，视觉逐位等价；
   - 分组重排（近似等价，独立开关）：alpha≈1 的不透明像素顺序无关，仅半透明边缘/
     decal 交界叠放次序可能变化，需截图对照验证后决定是否可用。
5. **blend 多样性**：船体普通 alpha（770/771），jitter 残影 additive（770/1），
   引擎 additive。→ 分组键 = (textureId, blendSrc, blendDst)；组内 quad 顺序保持
   收集序（additive 交换律宽松，普通 alpha 需保持次序，组内天然有序）。
   注意：贴图不重样的场景下（用户实测预判：舰船/武器贴图几乎不重样，仅少量武器
   重复），收益主体是消除每 sprite 的 pushMatrix/rotate/glBegin/glEnd 十余次
   JNI 调用（CPU 烘焙替代），与是否分组无关；P0 统计需同时量化「平均每组 quad 数」
   以评估分组重排的边际价值。
5. **矩阵捕获**：Sprite 的位移/旋转在 GL 矩阵栈里。收集时读取当前 modelview
   一次（glGetFloat 1 次 JNI）并 CPU 烘焙进顶点，替代原 pushMatrix/rotate。
   可在 Ship.render 级别缓存（一船所有 sprite 共享船体平移），进一步摊薄。
6. **非战斗场景污染**：Sprite 也被 UI/标题/改装界面使用。→ 收集器必须有作用域
   开关，仅在 CombatEngine 渲染区间（或更精确的舰船相关层）激活，其余走原路径。
7. **Sprite 变体**：renderNoBind / renderRegion* / renderWithCorners /
   renderAtCenterWithCornerColors 等（Sprite.java:200-426）UV 与顶点色逻辑各异。
   → 初版只拦截最高频的 `render(x,y)` / `renderAtCenter(x,y)`，其余透传。

## 2.5 P0 实测结论（25200 战斗帧均值，2026-08-16）

- quads=2238.5/帧（noBind≈0），distinct 组=259.2，**平均每组 8.64 quad**——
  贴图重复度并不低（top1 tex 为 additive 组、每帧约 125 quad，弹道/导弹/辉光类
  共享贴图主导），合批收益成立。
- **保序 run=1002/帧**：严格保序（视觉逐位等价）即可削减约 55% 绘制调用；
  分组重排理论上限 259 组/帧（约 88%），但有 painter's algorithm 风险，暂不上线。
- **禁区=821.8/帧（约 37%）**：以武器/船体损伤 decal 的 stencil 区域为主
  （profile 对照：WeaponDamageEffect.renderDamageDecals、DecalRenderer 内的
  stencil 操作），初版透传，后续评估「stencil 状态一致区域内合批」。
- 前提变化：`Sprite.render/renderNoBind` 此前已被 SpriteMixin 整体替换为单次
  native 调用，立即模式 JNI 开销已消除；P1 的剩余收益 = 纹理绑定去重 + drawcall 削减。

## 3. 方案设计（VBO quad 合批，明确不用 instanced）

> instanced 方案已在引擎合批中实测废弃（游戏上下文内 per-instance 属性获取异常，
> 见 combat-render-logic-optimization.md 任务 A）。本方案统一走 CPU 展开 + 环形 VBO。

### 3.1 架构（P1 已实现：流式严格保序）

```
SpriteBatch（common/render/spritebatch/）
├── SpriteBatch          接口：submitIfActive(...) / flushPending()
├── SpriteBatchImpl      流式合批器：组切换/禁区/边界即 flush，环形 VBO + glDrawElements
├── SpriteQuadPacker     纯逻辑顶点打包（几何公式同 SpriteRenderHelper，可单测）
├── SpriteBatchStats     P0 统计门面 + 战斗作用域标记
├── SpriteGroupStats     P0 统计纯逻辑
└── Mixin 接线
    ├── SpriteMixin（已有 @Overwrite render/renderNoBind）→ submitIfActive 接管或透传
    ├── CombatEngineScopeMixin（CombatEngine.render(Z) 头尾：作用域 + 帧末 flush）
    └── SpriteBatchBoundaryMixins（非 sprite 绘制边界 flush）：
        Ship.render 入口 / CustomCombatEntity.render（模组插件）/
        DecalRenderer.render / WeaponDamageEffect.beginDamageRender
        + 内部类直接调用：EngineBatchImpl.render、ShieldRenderHelper.render、
          ShipMaskMeshCache.render（clipToBounds 掩码）
```

- **流式严格保序**：连续同组（纹理×blend）的 sprite 合并为一个 run；组切换、
  禁区提交、非 sprite 绘制边界、作用域结束都会先 flush 已累积 run 再放行后续绘制，
  因此绘制相对顺序与原版逐位一致（分组重排开关不上线）。
- **烘焙**：submit 时 `glGetFloat(GL_MODELVIEW_MATRIX)` 一次 + 4 顶点 CPU 变换；
  flush 时以单位 modelview 绘制（顶点已是观察空间），与 flush 时刻的矩阵栈解耦。
- **边界插桩的动机**：延迟 flush 会打乱 sprite 与非 sprite 绘制（引擎、护盾、
  stencil 掩码/decal）的相对顺序——例如船体 quad 若延迟到 stencil 掩码写入之后才画，
  会把 decal 盖住。所有已知交错入口均插桩 flush；漏网边界（模组在层内直接画 GL）
  只可能造成局部重排，`enable` 开关可整体回退。
- **fallback 判定**（submit 入口检查，任一命中即先 flush 再透传原逻辑）：
  总开关关闭 / 非战斗作用域 / texClamp / `GLListManager.buildingList` /
  stencil test 开启 / scissor 开启 / 矩阵模式非 MODELVIEW。
- **状态恢复**：flush 后恢复 blend / 纹理绑定 / VBO 绑定 / client state / 矩阵模式，
  并以 run 内最后一个 sprite 的颜色执行 glColor4ub（复刻原版逐 sprite 设置
  当前颜色的残留语义）。

### 3.2 与引擎合批的差异

| 项 | 引擎合批 | 本方案 |
|---|---|---|
| 拦截点 | Engine.render（语义参数已知） | Sprite.render（通用 quad，需读矩阵烘焙） |
| 分组 | 阶段×纹理 | 连续同组 run（纹理×blend），严格保序 |
| flush 时机 | 每舰末尾（矩阵栈内） | 组切换/禁区/边界/帧末（顶点烘焙观察空间） |
| 顶点格式 | 20B（x,y,u,v+rgba） | 同左，索引模板 0,1,2/0,2,3 |

### 3.3 开关

- `ssoptimizer.render.spritebatch.enable`（默认 true，false 全部透传原逻辑）
- `ssoptimizer.render.spritebatch.stats`（默认 false，每 300 战斗帧输出
  组数/quad 数/保序 run/禁区命中率）
- 收益口径（P0 实测）：保序 run 1002 vs quads 2238 → drawcall 削减约 55%，
  另附纹理绑定去重（每 run 1 次 bind 替代每 sprite 1 次）。

## 4. 分阶段实施

- **P0 量化（已完成）**：统计管线 + 实测，结论见 2.5 节。
- **P1 核心管线（已实现，待视觉验证）**：流式严格保序合批 + 边界插桩 flush +
  禁区透传；视觉等价性由用户手工实测（对照开关 `-Dssoptimizer.render.spritebatch.enable=false`）。
- **P2 收益扩展**：Ship 级矩阵缓存（避免每 sprite 一次 glGetFloat）、
  武器 charge/glow sprite 变体纳入、renderNoBind 系列评估、
  stencil 状态一致区域（decal）内合批评估、分组重排开关的截图对照实验。
- **不做（初版）**：纹理图集重打包（改资源加载层，动静大，留作后续独立评估）；
  UI 场景合批；renderRegion*/renderWithCorners 变体；跨层合并（模组兼容性红线）。

## 5. 风险与兼容性清单

- GraphicsLib：层边界 flush 保证其全屏 pass（LightShader 在
  ABOVE_SHIPS_AND_MISSILES_LAYER 后执行）看到的中间帧缓冲与原版逐层绘制一致。
- jitter 残影：additive 多份拷贝经收集后组内有序合并，视觉等价；但其 display list
  副本路径走透传，不受合批影响。
- weapon skin / d-hull overlay / decal：均为独立纹理或 stencil 区域，分组键天然
  隔离或禁区透传，无需特殊处理。
- 回读类模组（读像素做 bloom/distortion）：合批不改变最终帧缓冲内容，仅改变
  drawcall 时序；层内次序保持，理论无影响，P1 用 GraphicsLib 场景实测验证。

## 已知问题（待复现）：攻势XIV 护盾 dome 串染

用户实机截图（2026-08）：攻势XIV 级护盾 dome 内表面出现红色小图标阵列，疑似图集纹理
+ 平铺 UV 扫过邻近贴图。2026-08-18 受控实验（`sso_xiv_probe` mission，图集开/关对照，
护盾常开 + 锁定视口各 13 帧）在 2819688（图集双轨制）之后**无法复现**，静态分析已排除
全部理论消费路径。可能原因：(a) 已被双轨制顺带修复；(b) 需特定条件（战役存档/模组组合/
操作序列）。待用户补充复现场景后，用 `-Dssoptimizer.atlas.shipweapon.dumpdir=<dir>`
导出的 atlas-regions.tsv（567619b）对照截图反推消费路径。复现命令见 sso_xiv_probe
mission（游戏目录 mods/ssoptimizer/data/missions/ 下）。
