# 超空间大地图渲染性能优化

## 背景

生涯模式超空间场景，地图越大帧率越低（实测 <3fps）。JProfiler 采样热点：

- `VertexStreamBufferPool.acquire` 42% 自身时间——**上游碎批次的放大器**，非根因。
- 调用链：`GL11.glEnd`（33.4%）→ `BridgeSupport.flushVertexStream` → `VertexStream.transferBuffer` → 每次落帧 acquire 新缓冲（`VertexStream.java:278-284`）。
- 上游：`TexturedStripRenderer.renderTexturedGradientQuad`（16.4%）、`TextStreamEmitter.emit`（11%，每文字 pass 独立 glEnd）、`enqueue/enqueueState`（8.6%）。

## 游戏侧根因（named 源码实证）

1. `BaseTiledTerrain.renderSubArea`（`BaseTiledTerrain.java:420`）：每瓦片每帧 `new Random(seed)` + 4 组 sin/cos 随机游走。视口内数百瓦片 → 每帧数千次三角函数 + Random 构造。
2. `HyperspaceTerrainPlugin.advance`（:539-578）：每帧两次 O(全网格 810×500≈405k) 循环（清理 activeCells + 更新玩家周围 subgrid）。
3. `BaseLocation.renderIndicators`（:354）：每帧全量遍历实体渲染 indicator；`UIIndicator` 无几何缓存（`FfIndicator` 已有 GLListManager 缓存可参照）。

视锥剔除已存在（AABB + 循环收缩），问题在视口内每瓦片的重复计算与碎批次 GL 调用。

## 优化项

### A1. HyperspaceTerrainPlugin.advance 双循环 delta 化（已回退）

- 实施后实测回归：门控把 subgrid 更新循环中时间驱动的 `CellStateTracker.advance` 一并降频，风暴闪烁动画以 interval 粒度跳变。
- 复核对源码后确认两段循环实际规模仅玩家周围 subgrid（约 1.2 万次轻量迭代/段），收窄门控收益为噪声级。
- 结论：整体回退，advance 恢复完全原版行为；helper、测试、系统属性 `ssoptimizer.hyperspace.advance.interval` 已一并移除。若后续实测 advance 确有可量化开销，正确方向是不触碰 `curr.advance` 时序的优化（如 tile 判定剔除）。

### A2. renderSubArea 瓦片随机参数缓存

- 目标：`BaseTiledTerrain.renderSubArea`（:402+）、`HyperspaceTerrainPlugin.renderQuad`（:253-442）。
- 方案：瓦片随机参数由 `(i, j, seed)` 确定性派生，`@Unique transient` 数组缓存懒生成；fader/signal 等动态量仍每帧计算。缓存 transient 避免 XStream 序列化（参照 GlowGeometryCache 模式）。
- 收益：消除每帧每瓦片的 Random 构造与静态三角函数。

### A3. 弧渲染方向向量缓存（实施修正）

- 实施时发现（named 源码 + javap 双重验证）：`UIIndicator` 原版已有 `GLListManager` display list 缓存（`UIIndicator.java:19/199-215`），「无几何缓存」的前提不成立。
- 真实热点：`EntityIndicator.renderRing`（`EntityIndicator.java:159-265`）每帧逐实体调用 `TexturedStripRenderer.renderArc/renderLineArc`，弧上每顶点即时算 sin/cos（`TexturedStripRenderer.java:65-105/:168-220`）。
- 方案（已实施）：`ArcStripRenderHelper` 按 `(step, count)` 键预计算 [cos,sin] 方向表（float[]，上限 1024 键），渲染时查表 + 与原版逐项相同的半径乘法，位级等价；弃用 display list（RT 桥接编译窗口语义风险）。
- 落点：`TexturedStripRendererMixin` 新增两个 @Overwrite 委托；sso-render `common/render/engine/ArcStripRenderHelper.java`。

### A4. glEnd 延迟落帧（bridge 层，中风险）

- 现状：`GL11.glEnd`（`GL11.java:107-116`）每次立即 `flushVertexStream` → transferBuffer + acquire。
- 方案：glEnd 只写 OP_END 标记，flush 推迟到：① 非流式命令 `enqueue`（现有逻辑本就先 flush，顺序语义保持）；② 帧尾 swap；③ 缓冲容量阈值。
- 论证：所有插入流之后的命令必经 `enqueue`→先 flush；渲染线程 `MergedBatchCommand` 本就合并相邻批次，大缓冲单批次与其等价。
- 边界审计：display list 编译窗口（保持立即模式）、阻塞通道（`blockingGet/flushForFenceWait` 已先 flush）、auxNative 路径。
- 收益：acquire 次数从 O(glEnd 次数) 降到 O(flush 点)，超空间场景每帧数万次 → 数百次；同时减少命令对象分配与 `synchronized(frameLock)` 进入次数。

### A5. TextStreamEmitter pass 合并（视 A4 收益降级）

- 目标：`TextStreamEmitter.emit`（sso-font，:63-112）每 pass 独立 glEnd。
- 方案：同图集页连续 pass 合并进同一段；A4 落地后收益递减，可降级为廉价整理（去重 glColor4ub 等）。

## 验证

- 单测：缓存键等价、delta 触发条件。
- `tools/smoke_test_game_launch.sh` + automation 基准：超空间大图场景前后帧时间对比。
- 每阶段独立验证后再推进下一阶段。
