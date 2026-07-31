# scope 语义映射片段

本目录存放按作用域（scope）拆分的语义映射片段，是构建期全量表的语义层数据源，
由 Phase 2 的 swarm 按 scope 并行产出。

## 文件约定

- 文件名：`{scope}-{platform}.tiny`，每个 scope 对 linux / windows 各一个文件
  （如 `campaign-fleet-linux.tiny` / `campaign-fleet-windows.tiny`）。
- 格式与 `../ssoptimizer-{platform}.tiny` 一致：Tiny v2（头部 `tiny	2	0	obf	named`），
  obf 列保持该平台的游戏真实混淆名，named 列为语义名；允许注释行
  （类行下 `\tc ...`、成员行下 `\t\tc ...`）。
- 注释按工作流约定记录：`来源(API-impl种子|javap取证|运行时验证) + 置信度 + 证据文件路径`。
- 片段允许只映射部分成员：未覆盖的成员在全量表中仍使用占位名（`f_<hash8>`/`m_<hash8>`）。
- scope 之间必须互不相交：同一混淆类/成员不得被两个 scope 映射，
  同一 named 类名不得被两个 scope 用于不同混淆类（`generateFullMappings` 与
  `mergeScopeFragments` 会报错并指明两个 scope）。

## 分层优先级

占位生成 < `ssoptimizer-identity.tiny` < **scope 片段** < 人工运行期表
`ssoptimizer-{platform}.tiny`（同混淆 key 高层胜出）。运行期权威表保持人工维护，
app 运行期管线不消费本目录。

## 校验

- `./gradlew :mapping:mergeScopeFragments`：校验全部片段（可解析、逐条目 jar 一致性、
  跨 scope 唯一性），输出汇总报告 `mapping/build/reports/scope-fragments.txt`；
  只校验并报告，不改动任何表。
- `./gradlew :mapping:generateFullMappings`：合并片段进全量表
  `mapping/build/generated/mappings/{platform}/ssoptimizer-{platform}-full.tiny`。
