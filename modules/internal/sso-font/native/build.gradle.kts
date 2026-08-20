plugins {
    id("ssoptimizer-native-module")
}

// 字体域原生子模块：FreeType 栅格化器（无 GL 依赖）。
ssoNative {
    moduleName.set("font")
    freetype.set(true)
}
