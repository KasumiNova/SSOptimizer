plugins {
    id("ssoptimizer-native-module")
}

// 加载优化域原生子模块：libpng 解码器。
ssoNative {
    moduleName.set("loading")
    libpng.set(true)
}
