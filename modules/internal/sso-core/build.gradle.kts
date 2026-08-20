plugins {
    id("ssoptimizer-module")
}

// 共享基础层：mapping 常量、ASM 分发框架、原生库加载、共享 Mixin Accessor、
// 通用资源工具、服务注册表，以及规模过小不独立成模块的杂项守卫/战役/经济优化。
dependencies {
    api(project(":modules:api:sso-api"))
    // BenchmarkProfiler 的采样接口（最终由 app 装配 shade 进 jar）
    implementation("tools.profiler:async-profiler:4.5")
}

tasks.named<JavaCompile>("compileJava") {
    // NativeRuntime 等 JNI 桥接类在本模块：自动导出头文件到根级共享目录（阶段 4 起各 native 子模块共用）
    options.headerOutputDirectory.set(layout.projectDirectory.dir("../../../native-headers/generated"))
}
