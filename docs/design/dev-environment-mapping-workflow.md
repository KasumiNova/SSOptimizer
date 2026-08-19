# SSOptimizer 渲染链路 Mapping 工作流

> **迁移声明（2026-08-03，最终态）**：SSOptimizer 已删除自带的 `:mapping` 构建模块。
> 全量 deobf 运行时由 NanoForge（`core/remap` + `NanoRemapTransformer`）承担，
> 映射表生成 / scope 片段 / 全量表合并 / named 游戏 jar 发布等全部 mapping 能力
> 已迁至 **SourceSector 仓**（`NanoForged/SourceSector` 的 `:mapping` 模块）。
> 本仓库不再有 mapping 模块、mapping 任务与映射资源，只作为 named 产物的消费方。

## 现状：Mapping 工作流已迁移至 SourceSector

- **映射能力归属**：Tiny 映射源、scope 片段、`generateFullMappings` / `mergeScopeFragments` /
  `scanMappingUsage` / `remapGameClasspathToNamed` 等生成与校验任务、`MappingVsGameJarConsistencyTest`
  等映射测试，全部位于 SourceSector 仓的 `:mapping` 模块。映射维护在 SourceSector 仓进行。
- **named 游戏 jar 发布**：本地开发需要 named 游戏 jar 时，在 SourceSector 仓执行
  `./gradlew :mapping:publishNamedGameJars`，产物发布到本地 Maven 仓库
  `build/named-game-repo/{platform}/`（坐标 `starsector.named:<jar基名>:0.98a-RC8-SNAPSHOT`，含 `-sources.jar`）。
- **SSOptimizer 消费侧**：app 以模块依赖消费 named 游戏本体 jar（`starfarer_obf` / `starfarer.api` /
  `fs.common_obf` / `fs.sound_obf`），仓库路径经 `-Psourcesector.namedRepo=...` 指定，默认取同级检出
  `../SourceSector/build/named-game-repo/windows`（见根 `gradle.properties` 与 `app/build.gradle.kts` 的 sdg 配置）。
  SNAPSHOT + app 端 `cacheChangingModulesFor(0)`：SourceSector 重发布后，IDE / 构建每次解析都取新产物。
- **首次 clone 或 `clean` 后**：先跑 SourceSector 仓的 `:mapping:publishNamedGameJars` 再同步 IDEA，
  否则本地仓库不存在，模块依赖解析失败会导致同步报错。
- **成员名直书 named 名**：游戏 jar 在磁盘上已是 named 版本，app 侧 `GameMemberNames` 已字面量化
  （91 个常量直接以 named 名书写，无运行期查表），ASM / Mixin 源码直接消费其常量。

## 命名规范
- 类名：语义优先，避免临时缩写
- 方法名：动词开头，体现动作
- 字段名：表达状态含义

> 上述规范现适用于 SourceSector 仓的映射维护（`C_`/`f_`/`m_` 前缀为生成占位名保留，人工命名不得使用）。

## 证据链规范
每条映射必须包含：
1. 热点来源
2. 反编译证据（方法签名与关键指令）
3. 调用链上下文

## 版本化与审查
- 每次映射变更单独 PR
- 变更必须写明命名理由
- 必须给出风险与回滚策略
