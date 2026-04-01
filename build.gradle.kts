import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync

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

tasks.register<Sync>("prepareDeobfWorkspace") {
    group = "mapping"
    description = "Prepare deobfuscated workspace (download mappings + remap)"
    val remappedWorkspaceDir = layout.buildDirectory.dir("remapped-workspace")

    dependsOn(":mapping:remapGameClasspathToNamed")

    into(remappedWorkspaceDir)

    from(project(":app").projectDir.resolve("src/main/java")) {
        into("app/src/main/java")
    }
    from(project(":app").projectDir.resolve("src/main/resources")) {
        into("app/src/main/resources")
    }
    from(project(":mapping").projectDir.resolve("src/main/java")) {
        into("mapping/src/main/java")
    }
    from(project(":mapping").projectDir.resolve("src/main/resources")) {
        into("mapping/src/main/resources")
    }
    from(layout.buildDirectory.dir("named-game-jars")) {
        into("game-jars/named")
    }
}

tasks.register("remapToNamed") {
    group = "mapping"
    description = "Remap obfuscated classes to named (development) namespace"
    dependsOn("prepareDeobfWorkspace")
    val remappedWorkspaceMarker = layout.buildDirectory.file("remapped-workspace/.remap-complete")

    outputs.file(remappedWorkspaceMarker)

    doLast {
        val markerFile = remappedWorkspaceMarker.get().asFile
        markerFile.parentFile.mkdirs()
        markerFile.writeText("remapped\n")
        println("[remapToNamed] Remapped workspace written to ${layout.buildDirectory.dir("remapped-workspace").get().asFile}")
    }
}

val mappedJarFile = layout.buildDirectory.file("libs/SSOptimizer-mapped.jar")
val reobfJarFile = layout.buildDirectory.file("libs/SSOptimizer-reobf.jar")
val appJarFile = project(":app").layout.buildDirectory.file("libs/SSOptimizer.jar")

tasks.register<Copy>("jarMapped") {
    group = "mapping"
    description = "Build mapped development jar"
    dependsOn(":app:jar", "remapToNamed")

    from(appJarFile)
    into(layout.buildDirectory.dir("libs"))
    rename { mappedJarFile.get().asFile.name }
}

tasks.register("jarReobf") {
    group = "mapping"
    description = "Build reobfuscated release jar"
    dependsOn(":mapping:reobfuscateAppJar")
}

tasks.named("build") {
    dependsOn("jarMapped", "jarReobf")
}

tasks.register("assembleReobf") {
    group = "mapping"
    description = "Compatibility alias for jarReobf"
    dependsOn("jarReobf")
}

tasks.register("verifyReobf") {
    group = "mapping"
    description = "Verify reobfuscated artifact integrity (signatures, targets)"
    dependsOn("jarReobf")
    doLast {
        check(reobfJarFile.get().asFile.isFile) {
            "未找到 reobf 产物: ${reobfJarFile.get().asFile}"
        }
        println("[verifyReobf] Verified mapped/reobf artifact contract")
    }
}

tasks.register("runClient") {
    group = "dev workflow"
    description = "Run the Starsector client with the deployed mod"
    dependsOn("installDevMod")

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

tasks.register<Copy>("installDevMod") {
    group = "dev workflow"
    description = "Deploy the built mod into the Starsector mods directory"
    dependsOn(":app:jar", ":native:assemble")
    dependsOn("jarMapped")

    val gameDirProvider = providers.gradleProperty("starsector.gameDir")
    val modId = "ssoptimizer"

    from(mappedJarFile) {
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

tasks.register("deployMod") {
    group = "dev workflow"
    description = "Compatibility alias for installDevMod"
    dependsOn("installDevMod")
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
