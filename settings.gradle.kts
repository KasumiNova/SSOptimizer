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

include(":modules:api:sso-api")
include(":modules:internal:sso-core")
include(":modules:internal:sso-ai")
include(":modules:internal:sso-ime")
include(":modules:internal:sso-save")
include(":modules:internal:sso-modopt")
include(":modules:internal:sso-automation")
include(":modules:internal:sso-loading")
include(":modules:internal:sso-render")
include(":modules:internal:sso-font")
include(":modules:internal:sso-app")

// 四个功能域 native 子模块：目录 <域>/native/，Gradle 项目名显式改称 native-<域>，
// 避免四个 "native" 撞名；产物 libssoptimizer_<域>.so / ssoptimizer_<域>.dll
include(":modules:internal:sso-render:native")
project(":modules:internal:sso-render:native").name = "native-render"
include(":modules:internal:sso-loading:native")
project(":modules:internal:sso-loading:native").name = "native-loading"
include(":modules:internal:sso-loading:native-texcompress")
project(":modules:internal:sso-loading:native-texcompress").name = "native-texcompress"
include(":modules:internal:sso-font:native")
project(":modules:internal:sso-font:native").name = "native-font"
include(":modules:internal:sso-ime:native")
project(":modules:internal:sso-ime:native").name = "native-ime"
