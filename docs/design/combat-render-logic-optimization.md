# 战斗渲染与逻辑重写优化（首轮）

本文档记录 SSOptimizer 首轮战斗侧重写优化的设计、语义等价性论证、已知偏差与开关。
实现分支 `feat/deobf-game`，四个方向并行落地：引擎渲染合批、护盾渲染优化、Ship 蒙版三角化缓存、CollisionGrid BVH。

## 背景与目标

Profiler 采样（大规模战斗）显示四类热点：

| 热点 | 占比 | 根因 |
|---|---|---|
| `Engine.render` | 14.4% | 每引擎槽 ~9 个 glBegin/glEnd、~16 次矩阵栈操作，全场每帧 1000+ drawcall |
| `Shield.render` | 6.3% | 每盾每帧 ~800 次三角函数 + ~800 次立即模式 JNI 调用 |
| `Ship.clipToBounds` → `Tesselator.renderTriangles` | ~14% | 每帧 `gluNewTess` + GLU 单调剖分，输入却是静态多边形 |
| CollisionGrid 建桶/查询 | 显著 | 每帧 5 份网格全量重建（上万次 addToCell + ArrayList 分配），大范围查询扫上千 cell |

## 任务 A：引擎渲染合批

- **Mixin**：`render/EngineRenderMixin`（`@Overwrite Engine.render(float)`）→ `EngineBatch`；`renderFighter` 由合批器内建战机公式接管。
- **管线**：每舰被渲染时对本舰全部引擎槽「收集 → 按 阶段×纹理ID 分组 → 立即 flush」，每舰最多 4 个 drawcall（strip-primary / strip-secondary / core / glow）。
- **三档模式**：`-Dssoptimizer.render.shipengine.mode=instanced|vbo|immediate`（默认 instanced）。
  - INSTANCED：`#version 330 compatibility` 着色器（3 个 program），`gl_VertexID` 模板展开 + `glDrawArraysInstanced`；矩阵用 compat 内建 `gl_ModelViewProjectionMatrix`。
  - VBO_BATCH：CPU 展开三角形 + 环形 VBO（孤儿化 + glBufferSubData）+ 固定管线 glDrawElements。
  - IMMEDIATE：回退 `EngineRenderHelper`。
  - 运行时探测 `GLContext.getCapabilities()`（游戏为 LWJGL2 兼容 profile，Linux 桌面驱动暴露 GL33+），探测/降级均打日志。
- **等价性**：引擎渲染全部 additive（770/1），舰内跨槽重排严格等价；alpha 的 int 截断用 `(int)`/`(byte)` 低 8 位逐位复现；display list 编译期（`GLListManager.buildingList`）检测并退回立即模式（已核实引擎 glow 不在游戏任何 display list 编译区间内，该检测为防御性）。
- **已知偏差**：`EngineRenderHelper`（IMMEDIATE 档）与当前反编译原版存在 7 处公式差异（helper 历史实现口径），合批路径全部按原版逐位实现；次轮统一口径。

## 任务 B：护盾渲染优化

- **Mixin**：`combat/ShieldRenderMixin`（`@Overwrite Shield.render(float)`）→ `ShieldRenderHelper`；`renderBand` 不再被调（保留原方法）。
- **算法**：旋转递推（复数乘 e^{iΔ} 步进，每顶点 4 乘 2 加，seed 每遍一次三角）替代逐点 sin/cos；扇形顶点缓存（key = var2 位比特 + segmentCount + radius，满展开后每帧命中）；两遍 additive FAN 合并为单次 `glDrawArrays(GL_TRIANGLES)`，band 单次 QUAD_STRIP；每盾每帧三角函数从 ~800 次降到 ~9 次。
- **对照算法**：`fillFanVerticesRaycast`（射线-外接正方形求交）由 `-Dssoptimizer.render.shield.algo=recurrence|raycast` 切换。基准（n=73，10 万次均值）：recurrence 112.6ns/盾，raycast 907.0ns/盾（约 8 倍劣化，每顶点 2 次三角 + 除法 + 开方），两实现逐点最大误差 7.06e-5。结论：保留 recurrence 为默认，raycast 留作对照，次轮决定去留。
- **等价性**：颜色/alpha 公式逐行照搬（int 截断、10° 边缘渐变、segmentBrightness）bit 级一致；blendFunc 两分支（fan 恒 770/1；band 按 renderAdditive 770/1 或 770/771）；死代码（ringTexture 第三遍，循环上界恒 2）不复刻。
- **显示列表**：Ship 受击 jitter 路径会把护盾 additive 渲染编进 display list；helper 检测 `GL_LIST_INDEX != 0` 时走 `renderImmediate`（逐行复刻原版立即模式）。

## 任务 C：Ship 蒙版三角化缓存

- **Mixin**：`render/TesselatorMixin`（`@Overwrite(remap=false)` 静态 `Tesselator.renderTriangles`）→ `ShipMaskMeshCache.render`；其余 Tesselator 方法不动。
- **算法**：`EarClippingTriangulator`（预处理去闭合重复/epsilon 重复/共线点 → O(n²) 非相邻边自交预检 → 鞋带公式统一 CCW → 全凸快路径三角扇 → 耳切 O(n²)，迭代上限保险）。
- **缓存**：`WeakHashMap<Bounds, CachedMesh>` 身份键 + 内容指纹（段数 + 逐段坐标位哈希）双保险；splitShip 整体替换 Bounds 时自然失效。
- **降级**（用户确认的例外）：自交/退化输入返回 null → 按 Bounds 限频（10s）WARN + 走逐行复刻的原 GLU 路径。真实船体语料（202 个 vanilla .ship）中 wasp.ship 与 paragon.ship 为非简单轮廓，走降级路径（性能与原版相同，不退化）。
- **开关**：`-Dssoptimizer.render.shipmasktess.enable`（默认 true，false 直接走 GLU 路径）。

## 任务 D：CollisionGrid 全局 BVH

- **Mixin**：扩展 `combat/CollisionGridQueryMixin`：`@Overwrite addObject/removeObject/getCheckIterator` 默认委托 `CollisionGridBvh`；`-Dssoptimizer.collisionGridBvh=false` 时 add/remove 走逐位复刻的原版网格写入、查询回退现有 fastutil 收集路径。
- **结构**：懒构建扁平 BVH——add 仅追加条目缓冲（cell 索引整数空间，向零截断 + clamp 逐位复刻），首次 query 时 Morton 排序 + 中点切分一次性物化（扁平 int SoA、显式栈遍历、无递归无分配）；整帧无查询的网格零构建成本。帧内 add 进溢出区线性补扫；remove 按移除矩形逐 cell 消耗条目可用矩形（插入序消耗，等价原版桶内 `List.remove`）。
- **基准**（500 实体重建 + 1000 混合查询 + 100 次 5000 大查询，ns/帧）：

| 实现 | 重建(add) | 混合查询 | 5000 大查询 |
|---|---|---|---|
| 原版网格 LinkedHashSet | 69,948 | 5,961,211 | 192,460 |
| fastutil 收集（回退路径） | 61,899 | 4,028,433 | 154,384 |
| 扁平 BVH | 7,540 | 1,248,088 | 58,703 |

- **语义等价清单**：cell 区间相交超集（未动精确 AABB）、向零截断与 clamp、null 条目、重复 add 查询端去重、快照迭代、迭代器 `remove()` 抛 UnsupportedOperationException、remove 幽灵条目行为——全部由随机 fuzz 对比测试（内置原版参考实现逐行移植 fixture）逐位验证。
- **已知偏差**：迭代顺序为 Morton 序（原版 cell 行优先扫描序）。游戏内全部调用方（CollisionEngine/各 AI）均自行做精确过滤，不依赖顺序；模组若隐式依赖顺序可通过开关回退。

## 开关总表

| 属性 | 默认值 | 作用 |
|---|---|---|
| `ssoptimizer.render.shipengine.enable` | `true`（本轮由 false 改为默认开启） | 引擎合批总开关 |
| `ssoptimizer.render.shipengine.mode` | `instanced` | 合批模式：instanced / vbo / immediate |
| `ssoptimizer.render.shield.enable` | `true` | 护盾渲染优化开关 |
| `ssoptimizer.render.shield.algo` | `recurrence` | 护盾顶点算法：recurrence / raycast（对照） |
| `ssoptimizer.render.shipmasktess.enable` | `true` | 蒙版三角化缓存开关 |
| `ssoptimizer.collisionGridBvh` | `true` | CollisionGrid BVH 开关（false 回退 fastutil 网格路径） |

## 验证记录

- 单测：`./gradlew :app:test` 全绿（含引擎实例打包逐位对比、护盾 264 参数化用例、耳切含真实船体语料、BVH fuzz 对比）。
- E2E：headless 启动冒烟（GL33 探测、INSTANCED 启用、护盾 helper 初始化日志正常）；ASTD 自动化战斗场景（arc_flare_aod7_basic）验证战斗内渲染与碰撞行为。

## 后续方向

- 统一 `EngineRenderHelper`（IMMEDIATE 档）与原版公式的 7 处口径差异。
- raycast 对照算法去留（当前实测劣于 recurrence 约 8 倍）。
- 引擎 over 层跨舰延迟 flush（layer 级 2 drawcall，近似等价，需独立开关评估）。
- wasp/paragon 类非简单轮廓的 winding-aware 三角化（消除 GLU 降级残留热点）。
