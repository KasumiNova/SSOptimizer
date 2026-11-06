---
description: "Mapped 开发规范：Java 模块禁止散写混淆名，named 成员名统一登记在 sso-core 的 mapping 常量包"
applyTo: "modules/**/src/{main,test}/java/**/*.java"
---

# Mapped 开发规范

## 核心原则

1. **Java 模块禁止直接散写混淆类名、字段名、方法名或描述符字面量。**
2. 游戏 jar 在磁盘上已是 named 版本，业务与 ASM/Mixin 源码直接使用 named 名；需要集中引用的类名、成员名和编译期签名登记在 `sso-core` 的 mapping 常量包。
3. Mixin 注解参数需要编译期常量时，使用 `GameMixinSignatures`，不得在业务源码中散落混淆签名。

## 常量位置

- **类名**：`modules/internal/sso-core/src/main/java/github/kasuminova/ssoptimizer/mapping/GameClassNames.java`
- **字段与方法 named 名**：`modules/internal/sso-core/src/main/java/github/kasuminova/ssoptimizer/mapping/GameMemberNames.java`
- **Mixin 编译期签名**：`modules/internal/sso-core/src/main/java/github/kasuminova/ssoptimizer/mapping/GameMixinSignatures.java`

三个常量类同属 `github.kasuminova.ssoptimizer.mapping` 包，本仓库没有独立 `:mapping` 模块。

## named 名来源

1. 映射表由 SourceSector 仓的 `:mapping` 模块维护，包括 Tiny 映射源、scope 语义片段、全量表生成与校验、named 游戏 jar 发布。
2. 在 SourceSector 仓运行 `./gradlew :mapping:publishNamedGameJars`，将 `starsector.named:*` 发布到本地 Maven 仓。SSOptimizer 默认同级 `../SourceSector/build/named-game-repo/windows`，也可用 `-Psourcesector.namedRepo=...` 覆盖。
3. 新增游戏成员引用前，先在 SourceSector 全量表确认 named 名，再在对应常量类中登记。

## 命名要求

1. named 名必须表达真实语义，例如 `CampaignSaveProgressDialog`、`writtenBytes`、`beginScreenOverlay`。
2. 命名空间必须保留原游戏或第三方包前缀，不得映射到 `github/kasuminova/ssoptimizer/**`。
3. 同一语义同时需要 internal 名和 dotted 名时，在 `GameClassNames` 中成对提供。

## 开发流程

1. 通过反编译、日志或运行时验证确认目标类与成员的真实职责。named jar 附带 `-sources.jar`，可在 IDE 中索引。
2. 在 `GameClassNames`、`GameMemberNames` 或 `GameMixinSignatures` 中补充常量。
3. 业务、ASM 与 Mixin 源码引用常量，不散写混淆字面量。
4. 游戏版本升级导致 named 名变化时，由 SourceSector 映射流程处理，并同步更新常量。

## 测试要求

- 引用成员名的处理器改动必须补充行为测试或烟测，处理器测试使用真实 named jar 或最小字节码夹具验证转换行为。
- `modules/internal/sso-app/src/test/java/github/kasuminova/ssoptimizer/bootstrap/RealBytecodeIntegrationTest.java` 使用 SourceSector named jar 验证 ASM 处理器，相关改动必须保持该测试通过。

## 例外处理

- 只有 Java 注解必须使用编译期常量且成员名常量表无法满足时，才允许在 `GameMixinSignatures` 中书写完整签名。
- 完整签名仍须集中维护，并在注释中说明对应的 named 语义。
