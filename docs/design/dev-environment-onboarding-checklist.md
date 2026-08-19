# SSOptimizer 新成员上手清单

## Day 0 环境准备
- 安装 JDK 25
- 验证 `java -version`
- 验证构建工具：
  - Linux / macOS：`./gradlew -v`
  - Windows：`gradlew.bat -v`
- 配置 Starsector 本地路径与读权限
- （可选）将开发参考文件放入 `.dev/` 目录（该目录不会被 git 跟踪）

## Day 1 运行验证
- 执行 `./gradlew doctor`（Windows：`gradlew.bat doctor`）
- 执行 `./gradlew :app:test`（Windows：`gradlew.bat :app:test`）
- 执行 `./gradlew :app:run`（Windows：`gradlew.bat :app:run`）
- 记录验证结果与环境信息

## Day 2 Mapping 与注入演练
- Mapping 工作流已迁移至 **SourceSector 仓**（`NanoForged/SourceSector` 的 `:mapping` 模块）：映射生成、校验与 named 游戏 jar 发布均在其侧
- 在 SourceSector 仓执行 `./gradlew :mapping:publishNamedGameJars` 后，本仓消费 `starsector.named:*` 本地仓产物（`-Psourcesector.namedRepo=...`）
- 选择一个热点方法，确认其 named 名并补齐 `GameMemberNames` / `GameMixinSignatures` 入口
- 完成一次最小注入演练并验证 Safe Profile 回退
