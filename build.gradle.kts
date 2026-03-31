import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec

plugins {
    base
}

group = "github.kasuminova.ssoptimizer"
version = "0.1.0-SNAPSHOT"

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.register("doctor") {
    group = "help"
    description = "Print build environment diagnostics"
    doLast {
        println("Project       : ${rootProject.name}")
        println("Group         : ${project.group}")
        println("Version       : ${project.version}")
        println("Java Runtime  : ${System.getProperty("java.runtime.version")}")
        println("Java Home     : ${System.getProperty("java.home")}")
        println("OS            : ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
    }
}

tasks.register("docsCheck") {
    group = "verification"
    description = "Validate development-environment baseline docs contract (JUnit)"
    dependsOn(":app:docsTest")
}

tasks.register("bootstrapDev") {
    group = "dev workflow"
    description = "Initialize and verify development environment"
    dependsOn("doctor", "docsCheck")
    doLast {
        println("✓ Development environment bootstrap complete")
    }
}

tasks.register("qualityGateLocal") {
    group = "dev workflow"
    description = "Run all quality gates (docs + tests + diagnostics)"
    dependsOn("docsCheck", ":app:test", "doctor")
    doLast {
        println("✓ All local quality gates passed")
    }
}

tasks.register("devCycle") {
    group = "dev workflow"
    description = "Daily development cycle: quality gates + build"
    dependsOn("qualityGateLocal", ":app:classes")
    doLast {
        println("✓ Dev cycle complete — ready to run")
    }
}

tasks.register("releasePrepLocal") {
    group = "dev workflow"
    description = "Pre-release local validation (quality gates + reobf verify)"
    dependsOn("qualityGateLocal", "verifyReobf")
    doLast {
        println("✓ Local release preparation complete")
    }
}

tasks.register("prepareDeobfWorkspace") {
    group = "mapping"
    description = "Prepare deobfuscated workspace (download mappings + remap)"
    doLast {
        println("[prepareDeobfWorkspace] Stub — will download/verify mappings and produce named-dev jar")
    }
}

tasks.register("remapToNamed") {
    group = "mapping"
    description = "Remap obfuscated classes to named (development) namespace"
    dependsOn("prepareDeobfWorkspace")
    doLast {
        println("[remapToNamed] Stub — will remap obf -> named")
    }
}

tasks.register("assembleReobf") {
    group = "mapping"
    description = "Remap build artifact back to obfuscated namespace for release"
    dependsOn(":app:jar")
    doLast {
        println("[assembleReobf] Stub — will remap named -> obf")
    }
}

tasks.register("verifyReobf") {
    group = "mapping"
    description = "Verify reobfuscated artifact integrity (signatures, targets)"
    dependsOn("assembleReobf")
    doLast {
        println("[verifyReobf] Stub — will check method signatures, mixin targets, entry points")
    }
}

tasks.register("runClient") {
    group = "dev workflow"
    description = "Run the Starsector client with the deployed mod"
    dependsOn("deployMod")

    doLast {
        println("[runClient] Launch configuration validated via JavaExec wiring")
    }
}

tasks.register("runSafe") {
    group = "dev workflow"
    description = "Run game with all injections disabled (safe profile)"
    dependsOn(":app:classes")
    doLast {
        println("[runSafe] Safe profile — not yet wired to game launch")
    }
}

tasks.register("runTrace") {
    group = "dev workflow"
    description = "Run game with verbose tracing enabled (trace profile)"
    dependsOn(":app:classes")
    doLast {
        println("[runTrace] Trace profile — not yet wired to game launch")
    }
}

tasks.register<Copy>("deployMod") {
    group = "dev workflow"
    description = "Deploy the built mod into the Starsector mods directory"
    dependsOn(":app:jar")

    val gameDirProvider = providers.gradleProperty("starsector.gameDir")
    val modId = "ssoptimizer"

    from(project(":app").layout.buildDirectory.file("libs/SSOptimizer.jar")) {
        into("jars")
    }
    from(rootProject.file("mod_info.json"))

    into(gameDirProvider.map { file(it).resolve("mods/$modId") })

    doLast {
        println("✓ Mod deployed to ${gameDirProvider.get()}/mods/$modId")

        val enabledModsFile = file(gameDirProvider.get()).resolve("mods/enabled_mods.json")
        if (enabledModsFile.exists()) {
            val parsed = JsonSlurper().parse(enabledModsFile)
            if (parsed is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val mutableJson = parsed as MutableMap<String, Any?>
                val enabledMods = (mutableJson["enabledMods"] as? List<*>)
                    ?.map { it.toString() }
                    ?.toMutableList()
                    ?: mutableListOf()
                if (!enabledMods.contains(modId)) {
                    enabledMods.add(modId)
                    mutableJson["enabledMods"] = enabledMods
                    enabledModsFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mutableJson)))
                    println("  → Added $modId to enabled_mods.json")
                }
            }
        }
    }
}

tasks.register<Copy>("installDevMod") {
    group = "dev workflow"
    description = "Install SSOptimizer jar and native runtime into the Starsector dev mod folder"
    dependsOn(":app:jar", ":native:assemble")

    val starsectorGameDir = providers.gradleProperty("starsector.gameDir")
        .orNull
        ?: error("Provide -Pstarsector.gameDir=/path/to/Starsector")

    val gameDir = file(starsectorGameDir)
    val modDir = gameDir.resolve("mods/ssoptimizer")

    from(project(":app").layout.buildDirectory.file("libs/SSOptimizer.jar")) {
        into("jars")
    }

    from(project(":native").layout.buildDirectory.file("lib/main/debug/libnative.so")) {
        rename("libnative.so", "libssoptimizer.so")
        into("native/linux")
    }

    from(rootProject.file("mod_info.json"))

    into(modDir)
}

tasks.register<JavaExec>("runClientExec") {
    description = "Internal JavaExec used to launch Starsector with the deployed mod"
    dependsOn("deployMod")

    doFirst {
        val gameDir = file(providers.gradleProperty("starsector.gameDir").get())
        val launchConfigFile = rootProject.file("launch-config.json")
        if (!launchConfigFile.exists()) {
            throw IllegalStateException("Missing launch-config.json in project root")
        }

        workingDir = gameDir
        setExecutable(gameDir.resolve("zulu25_linux/bin/java").absolutePath)

        @Suppress("UNCHECKED_CAST")
        val config = JsonSlurper().parse(launchConfigFile) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val jvmArgsConfig = config["jvmArgs"] as Map<String, List<String>>
        val commonArgs = jvmArgsConfig["common"] ?: emptyList()
        val linuxArgs = jvmArgsConfig["linux"] ?: emptyList()
        jvmArgs = commonArgs + linuxArgs

        @Suppress("UNCHECKED_CAST")
        val classpathJars = config["classpath"] as List<String>
        classpath = files(classpathJars.map { gameDir.resolve(it) })
        mainClass.set("com.fs.starfarer.StarfarerLauncher")
    }
}

tasks.named("runClient") {
    dependsOn("runClientExec")
}
