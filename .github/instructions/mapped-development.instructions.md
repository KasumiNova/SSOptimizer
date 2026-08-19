---
description: "Mapped 开发规范：app 模块禁止直接使用混淆名，成员名以 named 名直书（SourceSector 全量表命名空间），统一在 app 侧 mapping 常量包登记"
applyTo: "{app/src/main/java/**/*.java,app/src/test/**/*.java}"
---

# Mapped 开发规范

## 核心原则

1. **`app` 模块禁止直接写混淆类名、字段名、方法名或描述符字面量。**
2. 游戏 jar 在磁盘上已是 named 版本（NanoForge 全量 deobf + SourceSector 三命名空间全量表），
   业务与 ASM/Mixin 源码直接以 named 名书写，成员名集中登记在 app 侧常量表。
3. 若 Mixin 注解参数必须使用编译期常量，应把运行时签名集中放进 `GameMixinSignatures` 桥接常量表，
   禁止把混淆签名直接散落在 `app` 源码里。

## 该放在哪里

- **类名**：`app/src/main/java/github/kasuminova/ssoptimizer/mapping/GameClassNames.java`
- **字段/方法 named 表**：`app/src/main/java/github/kasuminova/ssoptimizer/mapping/GameMemberNames.java`（纯常量 holder，值即 named 名）
- **Mixin 编译期签名桥接**：`app/src/main/java/github/kasuminova/ssoptimizer/mapping/GameMixinSignatures.java`

> 三个常量类同属 app 侧 `github.kasuminova.ssoptimizer.mapping` 包；本仓库已无独立 `:mapping` 模块。

## named 名来源

1. **映射表维护**在 SourceSector 仓（`NanoForged/SourceSector` 的 `:mapping` 模块）：
   Tiny 映射源、scope 语义片段、全量表生成与校验、named 游戏 jar 发布均在其侧。
2. **named 游戏 jar 消费**：SourceSector 仓 `./gradlew :mapping:publishNamedGameJars` 发布
   `starsector.named:*` 到本地 Maven 仓，SSOptimizer 经 `-Psourcesector.namedRepo=...` 消费
   （默认同级 `../SourceSector/build/named-game-repo/windows`）。
3. `app` 源码只引用 named 名；新增需要引用的游戏成员时，先在 SourceSector 全量表确认其 named 名，
   再在 `GameClassNames` / `GameMemberNames` / `GameMixinSignatures` 中登记入口。

## 命名要求

1. named 名称必须表达真实语义，例如 `CampaignSaveProgressDialog`、`writtenBytes`、`beginScreenOverlay`。
2. 命名空间必须保留原游戏/第三方包前缀，不得映射到 `github/kasuminova/ssoptimizer/**`。
3. 同一语义如果同时需要 internal 名和 dotted 名，应在 `GameClassNames` 中成对提供。

## 开发流程

1. 先通过反编译、日志或运行时验证确认目标类/成员的真实职责（named jar 附带 `-sources.jar`，可直接在 IDE 索引）。
2. 在 `GameClassNames` / `GameMemberNames` / `GameMixinSignatures` 中补充 named 常量入口。
3. 回到业务 / ASM / Mixin 源码改用常量，删除原有混淆字面量。
4. 若游戏版本升级导致 named 名漂移，由 SourceSector 仓的映射流程处理，app 侧同步更新常量。

## 测试要求

- 引用成员名的处理器改动必须补充对应 `app` 测试或烟测验证（处理器测试对真实 named jar 字节码验证）。
- `RealBytecodeIntegrationTest` 对 SourceSector named jar 真实字节码验证全部 ASM 处理器，改动后必须保持绿色。

## 例外处理

- 只有在 Java 注解必须使用编译期常量、且成员名常量表无法满足时，才允许在常量表中直接书写完整签名。
- 即便属于上述例外，也必须把常量放到 `GameMixinSignatures` 集中维护，并在注释中说明对应的 named 语义。
