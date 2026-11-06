---
description: "Java 开发规范：模块依赖、ASM Processor、Mixin 注入、字节码转换与包结构"
applyTo: "modules/**/src/main/java/**/*.java"
---

# Java 开发规范

## 模块与包结构

- 功能模块只依赖 `:modules:api:sso-api` 与 `:modules:internal:sso-core`，跨域行为经 `ServiceRegistry` 解析。唯一既有例外是 `sso-font` 对 `sso-render` 的 `GlDispatch` 复用。
- `asm/` 只放 ASM Processor，`mixin/` 只放 Mixin 与 Accessor。
- 业务逻辑放在所属功能模块的 `common/` 领域包。被注入代码调用的 Hook 业务方法也属于 `common/`，不得放进 `asm/`。
- 注入代码调用业务实现，业务实现不得反向依赖具体 Processor 或 Mixin 类。

## 字节码改写优先级

1. 方法调用或字段访问重定向、方法头尾注入、Accessor 等场景必须使用 Mixin。
2. 仅在 Mixin 技术上不可实现时使用 ASM，例如跨指令上下文匹配，或 Mixin 接口注入无法覆盖的类结构修改。
3. 使用 ASM 时必须在 Processor 类注释中说明 Mixin 不可行的原因。
4. 禁止为同一目标重复注册 Mixin 与 ASM 改写，禁止使用 javaagent 或全类加载器兜底变换。

## ASM Processor 规范

- 实现 `AsmClassProcessor`，需要组合处理时使用 `CompositeAsmClassProcessor`。
- 目标类匹配必须精确，不得使用宽泛包名前缀。
- 普通 Launch 域处理器在 `SSOptimizerCorePlugin.registerAllProcessors()` 的注册链中装配；System 域与模组自建类加载器使用对应的专用注册通道。
- 改动必须有行为测试，使用最小字节码夹具或真实 named jar 验证转换后的指令与运行行为，不得只检查源码文本。

## 命名约定

- ASM Processor：`*Processor.java`，例如 `TextureObjectBindProcessor`。
- Mixin：`*Mixin.java`，例如 `GEngineRenderMixin`。
- Accessor：`*Accessor.java`，例如 `GEngineAccessor`。
- Hook 业务类按具体职责命名，可使用 `*Hooks.java`。
- 接口与桥接按暴露的领域能力命名，可使用 `*Bridge.java`。

## 注释

- `api` 包公开接口与核心模型必须写类头和公开方法简介，说明职责、调用前置条件、线程模型、返回值、异常语义与副作用。
- internal 实现、私有成员、常量与测试默认删除复述代码流程的注释，只在非直观并发边界、缓存失效条件、外部库缺陷绕过等歧义点保留单句说明。
- ASM Processor 与 Mixin 的类注释说明注入目标、动机和效果；ASM Processor 还需说明 Mixin 不可行的原因。
- 注释使用简体中文，不写设计稿章节号、决议代号、版本演变或需求来源，不使用长破折号。
