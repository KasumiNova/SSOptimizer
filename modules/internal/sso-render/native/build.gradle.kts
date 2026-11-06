plugins {
    id("ssoptimizer-native-module")
}

ssoNative {
    moduleName.set("render")
    windowsSystemLibs.set(listOf("opengl32"))
    linuxSystemLibs.set(listOf("GL"))
}
