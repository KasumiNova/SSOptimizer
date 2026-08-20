plugins {
    id("ssoptimizer-module")
}

// 加载优化模块：贴图懒加载/并行预载、原生 PNG 解码、资源索引与缓存、
// 声音/Janino 脚本并行加载、舰船武器图集（native 子模块在阶段 4 接入）。
dependencies {
    implementation(project(":modules:internal:sso-core"))
    implementation("com.github.luben:zstd-jni:1.5.7-3")
}

tasks.named<JavaCompile>("compileJava") {
    // JNI 桥接类（NativePngDecoder）在本模块：导出头文件到根级共享目录（阶段 4 起各 native 子模块共用）
    options.headerOutputDirectory.set(layout.projectDirectory.dir("../../../native-headers/generated"))
}
