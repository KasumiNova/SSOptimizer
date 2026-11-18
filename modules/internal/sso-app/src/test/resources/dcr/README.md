# DCR 测试字节码

以下三个 class 文件来自 DetailedCombatResults v5.4.2 的 `StarSectorDetailedCombatResults.jar`：

- `data/scripts/combatanalytics/SerializationManager.class`
- `data/scripts/combatanalytics/DetailedCombatResultsModPlugin.class`
- `data/scripts/combatanalytics/util/CompressionUtil.class`

`DcrBatchCompatibilityTest` 读取插件类字节码，验证旧版逐条保存结构的识别结果。资源放在 `dcr/` 前缀下，必须通过资源流读取，禁止作为 `data.scripts.*` 类加载。

必须保留 v5.4.2 样本作为旧版回归数据。新增 DCR 版本样本时使用独立资源目录，禁止覆盖旧样本。

DetailedCombatResults 使用 MIT 许可证，授权声明保存在 `DCR-LICENSE.txt`。
