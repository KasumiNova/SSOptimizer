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
- **tiny 映射源**：放到 `mapping/src/main/resources/mappings/ssoptimizer.tiny`
- **Mixin 编译期签名桥接**：放到 `mapping/src/main/java/github/kasuminova/ssoptimizer/mapping/GameMixinSignatures.java`

## 命名要求

1. named 名称必须表达真实语义，例如 `CampaignSaveProgressDialog`、`writtenBytes`、`beginScreenOverlay`。
2. 命名空间必须保留原游戏/第三方包前缀，不得映射到 `github/kasuminova/ssoptimizer/**`。
3. 同一语义如果同时需要 internal 名和 dotted 名，应在 `GameClassNames` 中成对提供。

## 开发流程

1. 先通过反编译、日志或运行时验证确认目标类/成员的真实职责。
2. 在 `ssoptimizer.tiny` 中新增或更新 named 映射。
3. 在 `GameClassNames` / `GameMemberNames` / `GameMixinSignatures` 中补充入口。
4. 回到 `app` 模块改用 mapping 常量，删除原有混淆字面量。
5. 为新增映射补充 `mapping` 模块单元测试，至少覆盖一次 class / field / method 查询。

## 测试要求

- 修改 `ssoptimizer.tiny` 后，必须跑 `mapping` 相关单元测试。
- 若映射被 `app` 模块的新逻辑消费，还必须补充对应 `app` 测试或烟测验证。

## 例外处理

- 只有在 Java 注解必须使用编译期常量、且运行时查表无法满足时，才允许保留 obfuscated 运行时签名。
- 即便属于上述例外，也必须把常量放到 `mapping` 模块集中维护，并在注释中说明对应的 named 语义。