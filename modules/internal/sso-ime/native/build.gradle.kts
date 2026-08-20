plugins {
    id("ssoptimizer-native-module")
}

// 输入法域原生子模块：Linux XIM + Windows IMM32。
// 两个实现都有条件编译保护（X11 缺失时 XIM 走 stub、非 _WIN32 时 IMM 走 stub），
// 因此两套源文件在任意平台目标均可编译；Windows 链接 user32/imm32。
ssoNative {
    moduleName.set("ime")
    x11.set(true)
    windowsSystemLibs.set(listOf("user32", "imm32"))
}
