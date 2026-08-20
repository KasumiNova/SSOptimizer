plugins {
    id("ssoptimizer-module")
}

// 异步 AI 模块：战斗 AI 并行调度、碰撞网格 BVH、相关 Mixin 织入。
dependencies {
    implementation(project(":modules:internal:sso-core"))
    implementation("it.unimi.dsi:fastutil:8.5.18")
    implementation("org.jctools:jctools-core:4.0.5")
}
