# Starsector Hybrid Weaver Agent Design

## Overview
This design details the implementation of a bytecode injection framework into Starsector for the SSOptimizer project. Given Starsector's `ModPlugin` loading lifecycle occurs too late to intercept core engine classes cleanly, we are adopting a `-javaagent` approach combined with a Hybrid Weaver dispatcher.

## 1. Agent Bootstrapping (启动引导层)
*   **Mechanism**: A standard Java Agent (`Premain-Class`), embedded within `SSOptimizer.jar`.
*   **Launch Hook**: Appended via `-javaagent:mods/ssoptimizer/SSOptimizer.jar` inside the custom `launch-config.json` JVM arguments.
*   **Initialization**: The agent captures class loading events via the `Instrumentation` API globally and registers our core `ClassFileTransformer`.

## 2. Dispatcher Pipeline (混合织入管线 - Hybrid Weaver)
When `com.fs.starfarer.*` or other classes are loaded into the JVM, they pass through a dual-stage interception pipeline:
*   **Stage 1: ASM Pre-filtering Processor**: 
    Extremely low-level or complex bytecode manipulations that SpongePowered Mixin cannot easily handle (such as specific raw instruction removals in the rendering pipeline) are processed here first using ASM Tree API.
*   **Stage 2: SpongePowered Mixin Service**: 
    The modified bytecode is then passed to the Mixin engine, which handles the majority of standard `@Inject`, `@Redirect`, and `@Overwrite` operations seamlessly.

## 3. Gradle Build Integration
*   **Shadowing**: Use `com.github.johnrengelman.shadow` plugin to bundle Mixin and ASM inside our mod jar, ensuring they are loaded alongside our Agent.
*   **Manifest Configuration**: Auto-generate `Premain-Class` and `MixinConfigs: mixins.ssoptimizer.json` headers into the fat-jar.
*   **Annotation Processing**: Hook the Mixin annotation processor into the `compileJava` phase to auto-generate `refmap.json` for name resolution.
*   **Deobfuscation Workspace**: Keep mapping plugins as reference-only (e.g. `source.jar` attached to the project) to provide developers with named method and field references, preventing full compilation chain contamination.

## Approvals
- Implementation strategy (Java Agent vs Dynamic Attach): Java Agent Approved
- Framework choice (Mixin vs ASM): Hybrid Weaver (ASM + Mixin) Approved
- Overall architectural integration: Approved by User