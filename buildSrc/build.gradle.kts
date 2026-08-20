plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    // SDG 插件（io.github.nanoforged:sdg）以 SNAPSHOT 发布在 mavenLocal
    mavenLocal()
}

dependencies {
    // 游戏依赖装配（named 仓 + gameLibraries）复用 SDG 轻量插件，供预编译脚本插件 apply
    implementation("io.github.nanoforged:sdg:0.1.0-SNAPSHOT")
}

gradlePlugin {
    // 仅使用预编译脚本插件（src/main/kotlin/*.gradle.kts），无二进制插件声明
}
