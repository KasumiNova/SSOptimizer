plugins {
    id("ssoptimizer-native-module")
}

// 渲染域原生子模块：glad + GL 初始化 + 全部 GL 渲染器。
// 不含 libpng/freetype/x11；Linux 链接 GL，Windows 链接 opengl32。
ssoNative {
    moduleName.set("render")
    windowsSystemLibs.set(listOf("opengl32"))
    linuxSystemLibs.set(listOf("GL"))
}
