---
description: "Mapped 开发规范：app 模块禁止直接使用混淆名，优先在 mapping 模块添加 named 表、Mixin 签名桥接与映射测试"
applyTo: "{app/src/main/java/**/*.java,mapping/src/main/java/**/*.java,mapping/src/main/resources/mappings/**/*.tiny,mapping/src/test/**/*.java}"
---

# Mapped 开发规范

## 核心原则

1. **`app` 模块禁止直接写混淆类名、字段名、方法名或描述符字面量。**
2. 新发现的游戏运行时符号，必须先在 `mapping` 模块补充 **named 名称**，再在 `app` 中消费。
3. 若 Mixin 注解参数必须使用编译期常量，应把运行时签名集中放进 `mapping` 模块的桥接常量表，禁止把混淆签名直接散落在 `app` 源码里。

## 该放在哪里

- **类名**：放到 `mapping/src/main/java/github/kasuminova/ssoptimizer/mapping/GameClassNames.java`
- **字段/方法 named 表**：放到 `mapping/src/main/java/github/kasuminova/ssoptimizer/mapping/GameMemberNames.java`
- **tiny 映射源**：按平台分别放到 `mapping/src/main/resources/mappings/ssoptimizer-linux.tiny` 与 `mapping/src/main/resources/mappings/ssoptimizer-windows.tiny`
- **Mixin 编译期签名桥接**：放到 `mapping/src/main/java/github/kasuminova/ssoptimizer/mapping/GameMixinSignatures.java`

## 两级映射表分工

1. **运行期权威表** `ssoptimizer-{platform}.tiny`（入库）：人工热点表，agent 内嵌，运行期 remap 与 `reobfuscateAppJar` 的唯一表来源。新增/修改语义映射只改这里。
2. **构建期全量表** `ssoptimizer-{platform}-full.tiny`（`mapping/build/generated/`，生成物不入库）：由 `generateFullMappings` 从人工表 + scope 语义片段 + 结构指纹占位名（`C_<hash8>`/`f_<hash8>`/`m_<hash8>`）确定性导出，分层优先级为 占位生成 < identity 片段 < scope 片段 < 人工表。供 `remapGameClasspathToNamed` 生成全量 named 编译 classpath。
3. **scope 语义片段** `mappings/scopes/{scope}-{platform}.tiny`（入库）：按作用域拆分的语义映射层（约定见该目录 `README.md`），只影响构建期全量表；用 `mergeScopeFragments` 校验并产出覆盖率/冲突报告。
4. `app` 源码只允许引用人工表中的 named 名、或 `ssoptimizer-identity.tiny` 中登记保持原名的游戏类；**禁止引用占位名**（`C_`/`f_`/`m_` 前缀），占位名在运行期 reobf 管线中不存在。
5. 工作流细节（生成/合并/漂移报告/版本升级/注释约定/identity 片段）见 `docs/design/dev-environment-mapping-workflow.md`。

## 命名要求

1. named 名称必须表达真实语义，例如 `CampaignSaveProgressDialog`、`writtenBytes`、`beginScreenOverlay`。
2. 命名空间必须保留原游戏/第三方包前缀，不得映射到 `github/kasuminova/ssoptimizer/**`。
3. 同一语义如果同时需要 internal 名和 dotted 名，应在 `GameClassNames` 中成对提供。

## 开发流程

1. 先通过反编译、日志或运行时验证确认目标类/成员的真实职责。
2. 在对应平台的 tiny（Linux / Windows）中新增或更新 named 映射，并保持两端 named 语义面一致。
3. 在 `GameClassNames` / `GameMemberNames` / `GameMixinSignatures` 中补充入口。
4. 回到 `app` 模块改用 mapping 常量，删除原有混淆字面量。
5. 为新增映射补充 `mapping` 模块单元测试，至少覆盖一次 class / field / method 查询。

## 测试要求

- 修改任一平台 tiny 后，必须跑 `mapping` 相关单元测试。
- 若映射被 `app` 模块的新逻辑消费，还必须补充对应 `app` 测试或烟测验证。

## 例外处理

- 只有在 Java 注解必须使用编译期常量、且运行时查表无法满足时，才允许保留 obfuscated 运行时签名。
- 即便属于上述例外，也必须把常量放到 `mapping` 模块集中维护，并在注释中说明对应的 named 语义。