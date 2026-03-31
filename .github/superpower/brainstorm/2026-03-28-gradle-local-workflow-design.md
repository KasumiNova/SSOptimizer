# SSOptimizer Gradle 本地工作流实现设计（定稿）

## 1. 目标

在不立即进入渲染优化编码前，先建立可复现、强约束、Minecraft 风格的本地 Gradle 工作流：

- 开发环境使用 deobf 语义（可读、可调试）；
- 产物发布前 reobf 回兼容符号；
- 质量门强阻断；
- 文档校验统一 JUnit（避免 Python 跨平台差异）。

## 2. 范围与约束

- 范围：本地开发工作流（非 CI 优先）。
- 命名：Minecraft 风格任务名。
- 质量门策略：强阻断。
- docsCheck：统一为 JUnit 测试任务。

## 3. 任务拓扑

### 3.1 入口任务

- `bootstrapDev`：初始化与环境检查。
- `devCycle`：日常开发主入口。
- `releasePrepLocal`：本地发布前总校验。

### 3.2 运行任务

- `runClient`：deobf 开发运行。
- `runSafe`：禁用注入的安全回归运行。
- `runTrace`：高日志/诊断运行。

### 3.3 质量门任务

- `docsCheck`：依赖 `:app:docsTest`。
- `qualityGateLocal`：强依赖 `docsCheck` + `:app:test` + `doctor`。

### 3.4 Mapping 生命周期任务

- `prepareDeobfWorkspace`
- `remapToNamed`
- `assembleReobf`
- `verifyReobf`

## 4. deobf → reobf 生命周期

产物流：

`obf 输入` → `named-dev（开发/调试）` → `build artifact` → `reobf artifact（发布）`

关键要求：

- `runClient` 默认运行 named-dev 路径；
- `releasePrepLocal` 必须经过 `verifyReobf`；
- 映射不一致、签名不匹配、目标缺失均阻断。

## 5. JUnit 化 docsCheck 设计

- 在 `app` 模块新增 `docsTest` source set（JUnit5）。
- 契约测试至少覆盖：
  1. 文档章节完整性
  2. README 链接完整性
  3. Mapping 流程关键字与要求
- 根任务 `docsCheck` 仅作为聚合入口，不直接调用外部脚本。

## 6. 最小改动清单

1. `build.gradle.kts`
   - 新增/重构：`bootstrapDev`、`qualityGateLocal`、`devCycle`、`releasePrepLocal`、`docsCheck`
2. `app/build.gradle.kts`
   - 增加 `docsTest` source set + `docsTest` task
3. `app/src/docsTest/java/...`
   - 新增文档契约测试类
4. `gradle/` 或 `buildSrc`
   - 实现 Mapping 管线任务

## 7. 推荐本地命令

- 日常：`./gradlew devCycle`
- 发布前：`./gradlew releasePrepLocal`
- 诊断：`./gradlew runTrace`
- 安全回归：`./gradlew runSafe`

## 8. 验收标准

1. `devCycle` 在干净环境可一次跑通。
2. `docsCheck` 仅依赖 JUnit，跨平台通过。
3. `releasePrepLocal` 未经 `verifyReobf` 不可通过。
4. 任务分组与描述可读（`tasks --all` 易发现）。

---

结论：采用“单根任务编排 + Minecraft 命名 + 强阻断质量门 + JUnit docsCheck + deobf开发/reobf发布”作为 SSOptimizer 的 Gradle 本地工作流实现方案。