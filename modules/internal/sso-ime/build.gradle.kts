plugins {
    id("ssoptimizer-module")
}

// 输入法模块：Linux XIM / Windows IMM32 输入法钩子与文本字段适配（native 子模块在阶段 4 接入）。
dependencies {
    implementation(project(":modules:internal:sso-core"))
}

tasks.named<JavaCompile>("compileJava") {
    // JNI 桥接类（LinuxXimNative/WindowsImmNative）在本模块：导出头文件到根级共享目录（阶段 4 起各 native 子模块共用）
    options.headerOutputDirectory.set(layout.projectDirectory.dir("../../../native-headers/generated"))
}
