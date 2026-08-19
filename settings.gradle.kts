pluginManagement {
    repositories {
        // SDG 插件（io.github.nanoforged.sectordevgradle.*）以 SNAPSHOT 发布在 mavenLocal
        mavenLocal()
        gradlePluginPortal()
    }
}

plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "SSOptimizer"

include(":app")
include(":native")
