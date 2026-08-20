plugins {
    id("ssoptimizer-module")
}

// 渲染线程模块：LWJGL 固定管线 bridge 镜像、渲染线程命令队列、状态仿真、
// sprite/引擎/粒子/护盾等渲染优化与相关 Mixin（native GL 族子模块在阶段 4 接入）。
dependencies {
    implementation(project(":modules:internal:sso-core"))
    implementation("it.unimi.dsi:fastutil:8.5.18")
    implementation("org.jctools:jctools-core:4.0.5")
}

tasks.named<JavaCompile>("compileJava") {
    // JNI 桥接类（SpriteBatchNative/EngineBatchNative 等）在本模块：导出头文件到根级共享目录（阶段 4 起各 native 子模块共用）
    options.headerOutputDirectory.set(layout.projectDirectory.dir("../../../native-headers/generated"))
}
