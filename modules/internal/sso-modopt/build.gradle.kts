plugins {
    id("ssoptimizer-module")
}

// 第三方模组适配模块：DCR 存档压缩/读档优化、AITweaks/ShipMastery 自建类加载器
// 接入 Launch transform 链的针对性适配、ASTD 战斗插件适配。
dependencies {
    implementation(project(":modules:internal:sso-core"))
    implementation("com.github.luben:zstd-jni:1.5.7-3")
}
