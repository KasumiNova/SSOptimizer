plugins {
    id("ssoptimizer-native-module")
}

ssoNative {
    moduleName.set("ime")
    x11.set(true)
    windowsSystemLibs.set(listOf("user32", "imm32"))
}
