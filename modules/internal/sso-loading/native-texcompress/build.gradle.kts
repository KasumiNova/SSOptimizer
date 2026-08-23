plugins {
    id("ssoptimizer-native-module")
}

// 加载优化域原生子模块：BC1/BC3/BC7 纹理压缩（vendor bc7enc + rgbcx，无第三方系统库）。
// JNI 生成头来自 sso-loading 的 compileJava -h 产出（根级 native-headers/generated），
// 插件已把该目录加进 privateHeaders 与交叉编译 include 路径，无需额外配置。
ssoNative {
    moduleName.set("texcompress")
}
