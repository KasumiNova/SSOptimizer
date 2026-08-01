# DetailedCombatResults 读档热点优化设计

> **历史文档注记（2026-08-02）**：本文成文于 javaagent 时代，文中 `:agent-api` /
> `:mod-optimizations` 模块、`SSOptimizerAgent` 装配点已随 NanoForge R4 coremod 化
> 收编进 `:app`（装配点改为 `SSOptimizerCorePlugin`）。优化本身的根因分析与字节码
> 方案仍然有效，下文保留原始记录。

## 目标

消除第三方模组 **DetailedCombatResults（DCR，id `DetailedCombatResults`，v5.4.2）** 在读档时 `onGameLoad` 的性能热点，并为「面向外部模组的性能优化」建立一个独立、可插拔的模块结构（`:agent-api` + `:mod-optimizations`），与游戏自身优化（`:app`）和自动化（未来独立模块）解耦。

本优化通过 SSOptimizer 的 `-javaagent` ASM 注入实现，**不修改 DCR 的任何文件**。

## 根因（Profile + 字节码双证据）

性能报告 `.references/热点_加载.xml`（JProfiler 调用树）：

| 节点 | 占比 / 耗时 |
|---|---|
| `DetailedCombatResultsModPlugin.onGameLoad(Z)V` | 48.8% / 16,992 ms |
| └ `SerializationManager.saveCombatResult`（聚合循环内 N 次调用） | 46.8% / 16,295 ms |
| 　└ `compress` → `Deflater.deflate`（级别 9） | 25.8% / 8,973 ms |
| 　└ `XStream.toXML` | 19.6% / 6,820 ms |

字节码（`javap -p -c`，本仓 jar 构建）证实 `onGameLoad(Z)V` 的 trim 重写循环：

```
145: invokestatic SerializationManager.clearSavedData:()V     // store="", _resultCache=null
148..155: List.iterator (var4 = 已裁剪列表)
157: 循环顶 (Iterator.hasNext)
164: ifeq 187 (出循环)
181: invokestatic SerializationManager.saveCombatResult:(Ldata/.../CombatResult;)V   // 唯一调用点
184: goto 157
226: return                                                   // 唯一 return
```

`saveCombatResult` 内部：`getAllSavedCombatResults().add(cr)` → `Collections.sort` → `getSerializer().toXML(整份列表)` → `saveValue(KEY, xml)` →（`compress` = `Deflater(9)` + Base64）。

因此循环第 k 次序列化/压缩 k 条记录，∑k = N(N+1)/2 ⇒ **O(N²)**。命中战报上限（`removeOldestEntriesIfOverSize`>0）或有过期模拟记录（`removeAgedSimulationData`>0）或数据损坏（`dataIsCorrupt`）时**每次读档都触发**，N≈上限，实测约 16.3 s。

> 同一 `saveCombatResult` 亦被每场战斗后（`CampaignEventListener.reportPlayerEngagement` / `SimulationCompleteListener`）调用一次，即每战重序列化+压缩全史（结构性 O(N)/次）——属 L3，本轮不做。

## 模块结构

```
:agent-api            共享 SPI（无生产依赖）
  ├─ api.AsmClassProcessor          （从 :app bootstrap 上移）
  ├─ api.CompositeAsmClassProcessor （从 :app bootstrap 上移；多处理器组合）
  └─ api.ExternalModOptimizer       （新 SPI：featureKey() + processors()）
:mod-optimizations    外部模组「性能优化」专属（不含自动化）
  └─ modopt.dcr.*  （DCR 处理器 + Zstd helper + DcrModOptimizer，经 ServiceLoader 注册）
:app                  implementation(:agent-api) + runtimeOnly(:mod-optimizations)
                      SSOptimizerAgent 经 ServiceLoader 装配，-Dssoptimizer.disable.<featureKey> 网关
(:native, :mapping    不变)
(未来 :mod-automation  ASTD 自动化——自动化不属于优化，另开模块)
```

为何不让模块 `implementation(project(":app"))`：`:app` 的 `jar` 任务是手工胖 agent jar（合并整个 `runtimeClasspath` 的 `zipTree`，`DuplicatesStrategy.EXCLUDE`，写 `Premain-Class`），依赖它会拖整包并成环。故引入中立的 `:agent-api`。`runtimeOnly(:mod-optimizations)` 即可让其类 + `META-INF/services` 并入 `SSOptimizer.jar`（`implementation(project(":mapping"))` 已证此打包路径）。

`ExternalModOptimizer` SPI：

```java
public interface ExternalModOptimizer {
    String featureKey();                          // 对应 -Dssoptimizer.disable.<key> 总开关
    Map<String, AsmClassProcessor> processors();  // 目标类内部名 → 处理器（已按类组合）
}
```

`SSOptimizerAgent.registerEngineProcessors` 内新增：
```java
for (ExternalModOptimizer opt : ServiceLoader.load(ExternalModOptimizer.class, ExternalModOptimizer.class.getClassLoader())) {
    if (Boolean.getBoolean("ssoptimizer.disable." + opt.featureKey())) { /* log skip */ continue; }
    opt.processors().forEach(transformer::registerProcessor);
}
```
（ServiceLoader 的 classloader 用 `ExternalModOptimizer.class.getClassLoader()` = agent/system loader，确保看见 `:mod-optimizations` 实现。）

## 类加载器约束（注入形态的决定因素）

注入进 DCR 的 `INVOKESTATIC helper` 运行在 DCR 的 **mod 类加载器**上下文，而 helper 由 **agent 父加载器**定义。父看不到子（mod）类，故 helper 方法签名只能用双方都可解析的中立类型（游戏 API / JDK / `String` / 基本类型），**不能出现 `CombatResult` 等 mod 类型**——与现有 `AutomationScreenshotHelper`（只收游戏 API 类型）一致。

已证（workflow 验证）：
- `SSOptimizer.jar` 仅经 `-javaagent` 加载 → JVM 自动并入 system/app classloader；mod 类加载器父委派可解析 `github/kasuminova/...`。ASTD（Janino loader）与 DCR（jar loader）皆走标准父委派，技术等价。
- `HybridWeaverTransformer` 是全局 `ClassFileTransformer`，按内部名精确匹配注册表；`BytecodeRemapper` 仅改 Tiny v2 表中的 `com/fs|sound` 名，DCR 名不在表中 → 对 DCR 是 no-op，不会损坏字节码。
- `SanitizingTransformer`/`ReflectionSanitizingTransformer` 对 DCR 均 no-op（仅改非法标识符/反射调用点）；`MixinBridgeTransformer` 跳过非 `com/fs/` 且非显式第三方目标的类（DCR 被跳过）。
- premain 在 `StarfarerLauncher.main` 前装好全部 transformer，早于 DCR 类加载。
- 注入须用 `COMPUTE_FRAMES` + override `getCommonSuperClass→java/lang/Object`；JDK 25 下 retransform 返回非 null 会强制校验，帧必须正确。

## L1 —【核心，默认开，零格式风险】消除 O(N²) 批量重写

向 **`SerializationManager`** 注入合成静态成员（同类内可访问其 private 成员）：

```java
// 伪代码（实际以 ASM 生成）
static boolean ssoptimizer$dirty;

public static void ssoptimizer$collect(CombatResult cr) {   // 替代循环里的 saveCombatResult
    getAllSavedCombatResults().add(cr);   // 重建并增长 _resultCache，与原逻辑一致
    ssoptimizer$dirty = true;             // 不序列化
}

public static void ssoptimizer$flush() {                    // onGameLoad return 前调用一次
    if (!ssoptimizer$dirty) return;
    java.util.List l = getAllSavedCombatResults();           // 返回 collect 重建后的非空 _resultCache
    java.util.Collections.sort(l);
    saveValue("CombatAnalytics_CombatResults_V4", getSerializer().toXML(l));  // 一次序列化+一次压缩
    ssoptimizer$dirty = false;
}
```

向 **`DetailedCombatResultsModPlugin`**：将 `onGameLoad(Z)V` 内 offset 181 的 `saveCombatResult` 调用 **redirect → `ssoptimizer$collect`**；在 offset 226 的 `return` 前 **inject `ssoptimizer$flush`**。

**等价性证明**：原循环每次 = `getAllSavedCombatResults().add(cr)` + sort + toXML + compress + saveValue；末态 = 全列表序列化压缩入 store + `_resultCache`=全列表。我的版本：collect 同样 add（`_resultCache` 同样重建增长），flush 末次序列化全列表入 store。**最终 `_resultCache` 与 store 与原循环逐字节等价**，仅省去 N−1 次中间写。要点：

- flush 用 `getAllSavedCombatResults()`（返回被 collect 重建的非空 `_resultCache`），**不**直接 `getstatic _resultCache`（`clearSavedData` 已置 null，靠 collect 重建）。
- `COMBATRESULTS_KEY` 是编译期常量被内联，flush 须 `ldc "CombatAnalytics_CombatResults_V4"` 字面量。
- 必须复用 `getSerializer()`（StaxDriver + PureJavaReflectionProvider + 全套 alias）与 `saveValue`（内部 `compress`+Base64），否则写出的 XML/格式 DCR 读不回。
- 非 trim 路径（offset 211 跳过循环）collect 不被调用，dirty 保持 false，flush no-op，store 不变——无冗余写。

收益：16.3 s ≈→ 0.4 s；**磁盘仍 Deflater 格式，卸载 SSOptimizer 无副作用**。

## L2 —【增益，默认开，可关】压缩层 Deflater(9) → Zstd

**关键约束：DCR 的 `util/Base64` 是非标准自定义编码**（字母表 `'?'..'~'`，含控制字符分帧逻辑），非 RFC Base64。若在 `SerializationManager.compress/decompress(String):String` 边界 redirect，则旧存档回退读必须忠实复刻这套诡异编解码，风险极高（读错=数据损坏，比性能问题更糟）。

因此 L2 下沉一层，在 **`CompressionUtil.compress(String):byte[]` / `decompress(byte[]):String`** 边界做替换（仅 JDK 类型 `String`/`byte[]`，跨加载器安全）：将 `CompressionUtil` 的这两个方法体改写为委派到 `modopt.dcr.DcrCompressionHelper`。DCR 的自定义 Base64 层原封不动地包在我的字节外（对我透明），helper 只处理 `byte[]`：

- **写**：`compress(xml)` = `Zstd(xml.utf8)`（zstd 帧自带魔数 `28 B5 2F FD`，无需额外前缀）。外层仍由 DCR 的 `Base64.convert(byte[])` 编码、落入存档。
- **读**：`decompress(bytes)` 先看前 4 字节是否 zstd 魔数 → 是则 Zstd 解；否则按旧 Deflate 流 `Inflater` 解（复刻 `CompressionUtil.decompress` 的 inflate→UTF8）。bytes 由 DCR 的 `Base64.convert(String)` 自定义解码而来——helper 不碰 Base64。
- **迁移**：旧存档读出后，下一次保存即转 Zstd（单向迁移）。

L1 的 flush→saveValue→`SerializationManager.compress`→`CompressionUtil.compress` 自动走 L2，二层一致。L2 仅改 `CompressionUtil`（独立于 L1 改的 SerializationManager/plugin），故各处理器目标类互不相同、**无需 CompositeAsmClassProcessor**。

子开关：`-Dssoptimizer.disable.dcrzstd=true` 时 `DcrModOptimizer.processors()` 不纳入 `CompressionUtil` 处理器 → DCR 原生 Deflater 路径不变。

**权衡（默认开，用户已确认）**：压缩串经游戏 XStream 落入存档文件；卸载 SSOptimizer（或开此子开关）后 DCR 原生 `Inflater` 无法解已迁移的 Zstd 串 → 判 `dataIsCorrupt` → 清空战报历史。README 须注明。

## L3 —【未来，结构性】消除每场战后的全史重序列化

改单键大 blob 为追加式/分片存储，需改 DCR 数据模型与存档结构，风险面大，本轮不做。

## 兼容性与风险

- L1 零格式变更（仍 Deflater），卸载安全。
- L2 前缀标签 + Deflater 读回退；卸载后丢历史（已接受）。
- 注入失败模式：HybridWeaver 吞异常返回 null（类不改而非崩溃）→ 处理器内须显式 success/failure 日志，使误注入可观测（遵守 Fail-Fast，不空 catch）。
- 字节码 offset / 常量池索引随 DCR 版本变化 → ASM 按方法名+描述符+指令模式匹配，**不**硬编码 offset；处理器 `reader.getClassName().equals(target)` 守卫。

## 验证方案

1. **TDD**（DCR jar 不在 test classpath → ClassWriter 手搓 fixture，mimic onGameLoad/SerializationManager 形状，transform→load→invoke）：
   - L1：fixture 模拟「N 次调用 saveCombatResult」的循环 + 一个可观测的「序列化计数器」，断言注入后序列化仅发生 1 次、最终列表与 store 内容等价。
   - L1 synth：断言注入后 SerializationManager 含 `ssoptimizer$collect/flush` 且行为正确。
   - L2：`DcrCompressionHelper` 往返（Zstd 写→读、旧 Deflater 串→读）、前缀识别、迁移；参考 `TerrainTileCompressionHelperTest`。
   - SPI：`ServiceLoader` 能发现 `DcrModOptimizer`，`processors()` 键覆盖两个目标类，`disable.dcrzstd` 生效。
2. **jmh**（参考 `SaveXmlWriterQueueBenchmark`）：N=上限的列表，对比「逐条重写 vs 合并一次」「Deflate-9 vs Zstd」吞吐。
3. **游戏烟测**（pure 装 `/mnt/windows_data/Games/Starsector098-linux-pure`）：
   - `SSOPTIMIZER_GAME_DIR=<pure> ./gradlew installDevMod` 部署新 jar；
   - `cp -r .../Starsector098-linux/mods/DetailedCombatResults <pure>/mods/` 并加入 `enabled_mods.json`（DCR 标 RC5 / install RC8，必要时改 mod_info gameVersion）；
   - 拷有战史存档（`save_Dev_3782943583724635391`，328 markers，19 MB，最快）；
   - `tools/smoke_test_game_launch.sh <pure> 120 game` 进主菜单后**手动** Continue/Load（仓库无读档自动化）；
   - `grep 'prior battle results using' <pure>/starsector.log` 取 `in <N>ms`，前后对比。

## 实施清单

1. `:agent-api`：建模块 + build.gradle；上移 `AsmClassProcessor`、`CompositeAsmClassProcessor` 到 `api` 包；新增 `ExternalModOptimizer`。
2. `:app`：`settings.gradle.kts` include；`build.gradle.kts` 加 `implementation(:agent-api)` + `runtimeOnly(:mod-optimizations)`；修 28 改 + 4 加 import；`SSOptimizerAgent` 加 ServiceLoader 装配。
3. `:mod-optimizations`：建模块 + build.gradle；三个目标类各一处理器——`DcrBatchSaveSynthProcessor`（SerializationManager：注入 `ssoptimizer$collect/flush` + `ssoptimizer$dirty`）、`DcrOnGameLoadProcessor`（plugin：redirect+inject）、`DcrCompressionProcessor`（CompressionUtil：改写 compress/decompress 体委派 helper）；`DcrCompressionHelper`、`DcrModOptimizer` + `META-INF/services`。注入用帧中立编辑 + asm-tree + COMPUTE_MAXS（flush 仅一处 `F_SAME` 帧），避免对复杂既有方法跑 COMPUTE_FRAMES。
4. TDD + jmh。
5. `./gradlew :app:jar` 构建；`installDevMod` 部署 pure；烟测取数。
6. README 增 L2 卸载权衡说明。
