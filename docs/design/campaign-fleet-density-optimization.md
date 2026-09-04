# 星域密集舰队性能优化

## 背景

生涯星域场景中大量舰队实体时帧率显著下降。JProfiler 采样热点：

- 渲染：`CampaignFleetView.render`（18.1%）→ `CampaignEngineGlowRenderHelper`（10.1%）+ `CampaignContrailBatchHelper.encodeContrail`（10.8%）。
- 逻辑：`StarSystem.advance`（31.9%）→ `CampaignFleet.advance`（25.1%）、`ModularFleetAI.advance`（7%）→ `TacticalModule.advance`（5.8%，含 `TimeoutTrackerMap.advance` 2.6%）。

## 现状分析（代码实证）

### 渲染侧（sso-render common/render/campaign/）

- `CampaignEngineGlowRenderHelper`：已是「单船单 draw」近最优形态，GlowGeometryCache 命中时只重写颜色字节。剩余开销 = RT 模式 pointer 快照深拷贝税 + 每船矩阵/状态切换。跨船合批已被论证放弃（渲染交错顺序/精度语义，javadoc :71-76）。
- `CampaignContrailBatchHelper.encodeContrail`：**无任何缓存**，每帧全量重编码所有点；每点含除法、段相交检测、proximity `Math.sqrt`；fadeOut 完成、亮度恒 0 的老化尾段也全量处理。
- `CampaignContrailAdvanceHelper`：逐点推进不做视口裁剪（既有语义决策，`CampaignFleetViewMixin` javadoc :21-22，本轮不改变）；每帧 `new ArrayList`（removals）。

### 逻辑侧（游戏 named 源码）

- `BaseLocation.advance`（:605-707）：交战检测所有可交战舰队两两配对 **O(F²)**，无空间分桶。
- `TacticalModule.advance`（:215-509）：interval 内每舰队扫描全部舰队做可见性判定，总体 **O(F²)**。
- `CampaignFleet.advance`（:727）：每帧遍历全局 `SpecStore.getCampaignHullMods()`。
- `TimeoutTrackerMap.advance`：每舰队每帧 O(n) 全 entry 遍历（entry 少，收益不成比例，**暂不动**）。

## 优化项

### B1. encodeContrail 老化段简化

- 目标：`CampaignContrailBatchHelper.encodeContrail`（:165-337）。
- 方案：fadeOut 完成且 maxBrightness 恒 0 的尾段点跳过相交检测、proximity sqrt、亮度除法，直接写零亮度顶点（alpha=0 视觉等价）；proximity 计算限定 fadeSource 活跃头部段。
- 约束：亮度双段线性公式、fadeSource 跨尾迹传递等副作用写回必须保留原版语义（helper 内注释逐条对齐原版，改动逐条复核）。

### B2. advanceContrail 全老化短路 + 分配复用

- 目标：`CampaignContrailAdvanceHelper`（:42-50、:52-85）。
- 方案：组内全部点 elapsed==duration 且 fadeOut 完成时只做移除判定；removals 改成员字段复用。
- 不改变「LOD 不裁 advance」的既有语义。

### B3. CampaignFleet 全局 hullmod 遍历缓存（已放弃，审计存证）

审计结论（named 源码实证）：
1. `SpecStore.getCampaignHullMods()` 内部已是懒初始化静态缓存（`SpecStore.java:80,2103-2115`），会话期间从不失效重建——无优化空间。
2. 入列的唯一原版实现 `PhaseField.advanceInCampaign`（`PhaseField.java:35-44`）有不可省略的每帧副作用（检测 `$justToggledTransponder` memory 标志并改写探测距离、0.1 天过期写回）。
3. vanilla 列表长度 = 1，收益噪声级。第三方模组实现副作用不可判定。
结论：放弃，零代码改动。

### B4. BaseLocation 交战检测近距分桶（默认开，可回退）

- 目标：`BaseLocation.advance`（:605-707，"Checking combat initiation" Profiler 段）。
- 方案：只对玩家附近（sensor 距离上限内）舰队两两配对；远处舰队降低配对频率。行为等价性需实测（交战触发时机不能变）。
- 开关：`-Dssoptimizer.campaign.combatPairing=false` 回退。

### B5. TacticalModule 扫描距离预过滤（默认关闭）

- 目标：`TacticalModule.advance`（:215-509）。
- 方案：sensor 距离上限粗过滤后再进 `getVisibilityLevelTo` 精判。**改变 AI 感知路径，默认关闭**，`-Dssoptimizer.campaign.tacticalPrefilter=true` 显式开启。

### 暂不做

- pointer snapshot 深拷贝税（`BufferSnapshotPoolImpl`）双缓冲/直接提交：架构级，待本轮落地后重新 profile 再立项。
- `TimeoutTrackerMap.advance` 惰性化：语义风险高、收益不成比例。
- 渲染线程回放侧已达 63.8% 忙（`render-parallel-recording.md:10-11`）：主线程减量后需重新 profile 确认瓶颈是否迁移。

## 验证

- 单测：encode 输出位级抽样对比、短路条件。
- automation 基准：密集舰队场景前后帧时间对比。
