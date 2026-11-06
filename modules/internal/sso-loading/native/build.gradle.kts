plugins {
    id("ssoptimizer-native-module")
}

ssoNative {
    moduleName.set("loading")
    libpng.set(true)
}
