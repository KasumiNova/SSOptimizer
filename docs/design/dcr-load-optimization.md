# DetailedCombatResults 读档热点优化设计

## 目标

消除第三方模组 DetailedCombatResults（DCR，id `DetailedCombatResults`，v5.4.2）读档时 `onGameLoad` 的性能热点。优化全部经 Mixin 实施，不修改 DCR 的任何文件，实现位于 `:modules:internal:sso-modopt`。

## 根因

性能报告 `.references/热点_加载.xml`（JProfiler 调用树）：

| 节点 | 占比 / 耗时 |
|---|---|
| `DetailedCombatResultsModPlugin.onGameLoad(Z)V` | 48.8% / 16,992 ms |
| 聚合循环内 N 次 `SerializationManager.saveCombatResult` | 46.8% / 16,295 ms |
| 其中 `compress` 的 `Deflater.deflate`（级别 9） | 25.8% / 8,973 ms |
| 其中 `XStream.toXML` | 19.6% / 6,820 ms |

`onGameLoad(Z)V` 的裁剪重写循环：`clearSavedData()` 之后遍历裁剪后的列表，对每条战报调用一次 `saveCombatResult`（方法内唯一调用点）。`saveCombatResult` 内部依次执行 `getAllSavedCombatResults().add`、`Collections.sort`、`getSerializer().toXML(整份列表)`、`saveValue(KEY, xml)`，整体包在 `Throwable` 捕获中，失败时记录日志并调用 `Helpers.printErrorMessage`。循环第 k 次序列化并压缩 k 条记录，总量为 N(N+1)/2，即 O(N²)。

命中战报数量上限、存在过期模拟记录或数据损坏时，每次读档都会触发该循环。

同一 `saveCombatResult` 也在每场战斗后调用一次，每战重序列化全史，属 L3，本轮不做。

## 模块结构与开关

- `modopt.dcr.DcrBatchSaveWindow`：批量保存窗口，只持有线程与剩余次数。
- `modopt.dcr.DcrCompressionHelper`：压缩内核。
- `mixin.modopt.dcr.DcrOnGameLoadBatchMixin`、`DcrSerializationManagerBatchMixin`：L1。
- `mixin.modopt.dcr.DcrCompressionUtilMixin`：L2。
- 三个 Mixin 均注册在 `mixins.ssoptimizer.json`。`-Dssoptimizer.disable.dcr=true` 由 `SSOptimizerMixinConfigPlugin` 在织入期跳过 L1 的两个 Mixin，不影响 L2。

DCR 的类不在编译类路径上，Mixin 以字符串目标指定，`@Redirect` 的参数与返回类型只使用 JDK 与 XStream 类型。

## L1：消除 O(N²) 批量重写

`DcrOnGameLoadBatchMixin` 重定向 `onGameLoad` 内唯一的 `List.iterator()`，以裁剪后列表的长度开启批量窗口；在方法 `RETURN` 处关闭窗口。

`DcrSerializationManagerBatchMixin` 对 `saveCombatResult` 做三处重定向：

1. 保存入口的 `getAllSavedCombatResults()`：登记一次保存并判定是否跳过。计数放在入口，保存内部后续步骤抛出异常也不会漏计。
2. `XStream.toXML`：被判定跳过时返回 `null`，不序列化。
3. `saveValue`：序列化结果为 `null` 时不落盘。

窗口内除最后一次外的保存只追加并排序列表，最后一次沿用原方法的完整路径，包括异常捕获与错误提示。全部保存都未抛出异常时，`_resultCache` 与存档内容和逐条保存的最终结果一致。磁盘格式不变，卸载 SSOptimizer 无副作用。

窗口只允许持有线程关闭，不支持重入；非持有线程的保存不受影响。

## L2：压缩层 Deflater(9) 改为 Zstd

DCR 的 `util/Base64` 是非标准自定义编码，因此替换点下沉到 `CompressionUtil.compress(String):byte[]` 与 `decompress(byte[]):String`，只处理 `byte[]`，不触碰外层编码。

- 写：Zstd 压缩，帧自带魔数 `28 B5 2F FD`。
- 读：先检查前 4 字节是否为 zstd 魔数，是则 Zstd 解压，否则按旧 Deflate 流解压。
- 迁移：旧存档读出后，下一次保存转为 Zstd。

`DcrCompressionUtilMixin` 以 `@Overwrite` 整体替换这两个方法并委派 `DcrCompressionHelper`。

权衡：压缩串随存档落盘，卸载 SSOptimizer 后 DCR 原生 `Inflater` 无法解 Zstd 串，会判定数据损坏并清空战报历史，存档本体不受影响。README 已注明。

## L3：消除每场战后的全史重序列化

需要改为追加式或分片存储，涉及 DCR 数据模型与存档结构，风险面大，本轮不做。

## 失败语义与风险

- 批量窗口中最后一次保存失败时，存档不会保留逐条保存留下的中间快照；原方法照常记录错误并返回。
- `onGameLoad` 异常逃逸时窗口不会被 `RETURN` 注入点关闭，但读档已经失败，窗口也不会被复用，再次开窗时 `begin` 会重置状态。
- DCR 版本变化后，调用点形状可能改变。`DcrBatchMixinAnchorTest` 读取随仓库提供的真实 DCR 字节码，校验 `onGameLoad` 内 `List.iterator` 以及 `saveCombatResult` 内 `getAllSavedCombatResults`、`toXML`、`saveValue` 调用点各恰有一个。Mixin 的 `expect = 1` 在织入期提供第二层保护。

## 验证

- `DcrBatchMixinAnchorTest`：锚点唯一性。
- `DcrBatchSaveWindowTest`：窗口的跳过、关闭与线程归属逻辑。
- `DcrCompressionHelperTest`：Zstd 往返、旧 Deflate 读取与损坏数据处理。
- 游戏烟测：渲染线程开启与关闭两种模式下启动均通过，日志无 Mixin 织入错误。
- 读档裁剪路径的运行期行为与耗时没有实机验证，需要带战史存档读档，并用 `prior battle results using` 日志对比耗时。
