plugins {
    id("ssoptimizer-module")
}

// 字体渲染模块：FreeType 原生栅格化、TTF 字体包生成/缓存、高 DPI 缩放、
// 位图字体渲染优化（native FreeType 子模块在阶段 4 接入）。
dependencies {
    implementation(project(":modules:internal:sso-core"))
    // 动态字形图集经 render 模块的 GlDispatch 提交纹理上传/上下文重建
    implementation(project(":modules:internal:sso-render"))
    implementation("com.github.luben:zstd-jni:1.5.7-3")
}

tasks.named<JavaCompile>("compileJava") {
    // JNI 桥接类（NativeFontRasterizer）在本模块：导出头文件到根级共享目录（阶段 4 起各 native 子模块共用）
    options.headerOutputDirectory.set(layout.projectDirectory.dir("../../../native-headers/generated"))
}
