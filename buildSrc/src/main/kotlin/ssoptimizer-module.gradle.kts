/**
 * SSOptimizer Java 模块共享约定：工具链、编码、仓库、游戏编译期依赖。
 *
 * 适用于所有纯 Java 子模块（api/core/各功能域模块/app 除外——app 由 SDG mod 插件装配）。
 * 游戏 jar 以 compileOnly 供给（运行时由游戏 classpath / NanoForge 提供），不会打进产物。
 * named 游戏仓与 gameLibraries 由 SDG 轻量插件（SdgGameDepsPlugin）装配，此处不再重复实现。
 */
import io.github.nanoforged.sdg.SdgGameDepsPlugin

plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenLocal()
    mavenCentral()
    // RFB / LaunchWrapper（IClassTransformer 编译期依赖）
    maven { url = uri("https://nexus.gtnewhorizons.com/repository/releases/") }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

// ---- SDG 轻量插件：named 仓 4 jar compileOnly + gameLibraries（afterEvaluate 装配） ----
// sourceRepo 默认同级 SourceSector 检出（-Psourcesector.namedRepo 覆盖）、默认排除 xstream-1.4.10
apply<SdgGameDepsPlugin>()

dependencies {
    // ---- 运行时由 NanoForge / 游戏提供，编译期对齐版本（不进产物） ----
    compileOnly("io.github.nanoforged:NanoForge:0.1.0-SNAPSHOT") { isTransitive = false }
    compileOnly("com.gtnewhorizons.retrofuturabootstrap:RetroFuturaBootstrap:1.0.12") { isTransitive = false }
    compileOnly("org.ow2.asm:asm:9.8")
    compileOnly("org.ow2.asm:asm-commons:9.8")
    compileOnly("org.ow2.asm:asm-tree:9.8")
    compileOnly("net.fabricmc:sponge-mixin:0.16.3+mixin.0.8.7")
    compileOnly("org.apache.logging.log4j:log4j-1.2-api:2.25.2")
    compileOnly("org.apache.logging.log4j:log4j-api:2.25.2")
    // 加载期噪音聚合过滤器的 log4j2 层实现（生产运行时由 NanoForge 提供 log4j-core）
    compileOnly("org.apache.logging.log4j:log4j-core:2.25.2")
}
