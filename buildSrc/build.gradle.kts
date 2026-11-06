plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    // SDG SNAPSHOT 插件从 mavenLocal 解析。
    mavenLocal()
}

dependencies {
    implementation("io.github.nanoforged:sdg:0.1.0-SNAPSHOT")
}

gradlePlugin {
}
