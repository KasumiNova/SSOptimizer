plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "SSOptimizer"

include(":app")
include(":agent-api")
include(":mod-optimizations")
include(":mapping")
include(":native")
