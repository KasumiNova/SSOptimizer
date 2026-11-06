pluginManagement {
    repositories {
        // SDG SNAPSHOT 插件从 mavenLocal 解析。
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
include(":modules:internal:sso-debug")
include(":modules:internal:sso-loading")
include(":modules:internal:sso-render")
include(":modules:internal:sso-font")
include(":modules:internal:sso-app")

// native 子模块必须显式改名，避免多个项目使用相同的 Gradle 项目名。
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
