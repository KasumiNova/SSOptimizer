plugins {
    id("ssoptimizer-module")
}

// 调试服务模块：游戏内 localhost HTTP RPC 服务（动态脚本编译执行、L1/L2 热重载）。
// 默认关闭，-Dssoptimizer.debug.enabled=true 启用；设计见 docs/design/debug-capability-design.md。
dependencies {
    implementation(project(":modules:api:sso-api"))
    implementation(project(":modules:internal:sso-core"))

    // JSON 序列化（RPC 编解码）：零传递依赖，随 sso-app shade 进 coremod jar
    implementation("com.google.code.gson:gson:2.13.1")
}
