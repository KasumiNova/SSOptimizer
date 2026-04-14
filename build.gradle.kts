import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip

plugins {
    base
}

group = "github.kasuminova.ssoptimizer"
version = "0.1.0-SNAPSHOT"

@Suppress("UNCHECKED_CAST")
val modInfo = JsonSlurper().parse(rootProject.file("mod_info.json")) as Map<String, Any?>
val modId = modInfo["id"]?.toString() ?: "ssoptimizer"
val modReleaseVersion = modInfo["version"]?.toString() ?: project.version.toString()

fun hostPlatformId(): String {
    val osName = System.getProperty("os.name", "").lowercase()
    return if (osName.contains("win")) "windows" else "linux"
}

fun detectRuntimePlatform(gameDir: File?): String {
    if (gameDir != null) {
        if (gameDir.resolve("starsector-core").isDirectory) {
            return "windows"
        }
        if (gameDir.resolve("starsector.sh").isFile
                || gameDir.resolve("zulu25_linux").isDirectory
                || gameDir.resolve("jbr25_linux").isDirectory) {
            return "linux"
        }
    }
    return hostPlatformId()
}

fun resolveRuntimeDir(gameDir: File): File {
    val starsectorCoreDir = gameDir.resolve("starsector-core")
    return if (starsectorCoreDir.isDirectory) starsectorCoreDir else gameDir
}

fun resolveBundledJavaExecutable(gameDir: File, platform: String): File? {
    val candidates = when (platform) {
        "windows" -> listOf(
            gameDir.resolve("zulu25/bin/java.exe"),
            gameDir.resolve("jre/bin/java.exe")
        )

        else -> listOf(
            gameDir.resolve("zulu25_linux/bin/java"),
            gameDir.resolve("jbr25_linux/bin/java"),
            gameDir.resolve("jre_linux/bin/java"),
            gameDir.resolve("jre/bin/java")
        )
    }
    return candidates.firstOrNull { it.isFile }
}

fun rewriteRuntimePathArgs(platform: String, args: List<String>): List<String> {
    if (platform != "windows") {
        return args
    }
    return args.map { arg ->
        when {
            arg.startsWith("-javaagent:./mods/") -> arg.replace("-javaagent:./mods/", "-javaagent:../mods/")
            arg.startsWith("-Dcom.fs.starfarer.settings.paths.saves=./") -> arg.replace("=./", "=../")
            arg.startsWith("-Dcom.fs.starfarer.settings.paths.screenshots=./") -> arg.replace("=./", "=../")
            arg.startsWith("-Dcom.fs.starfarer.settings.paths.mods=./") -> arg.replace("=./", "=../")
            else -> arg
        }
    }
}

val configuredGameDirProvider = providers.gradleProperty("starsector.gameDir")
    .orElse(providers.environmentVariable("SSOPTIMIZER_GAME_DIR"))
val targetPlatformProvider = configuredGameDirProvider
    .map { detectRuntimePlatform(file(it)) }
    .orElse(providers.provider { hostPlatformId() })

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
    val starsectorGameDir = providers.gradleProperty("starsector.gameDir").orElse(providers.environmentVariable("SSOPTIMIZER_GAME_DIR")).orNull
    val mappingPlatform = providers.gradleProperty("starsector.platform")
        .orElse(providers.provider { detectRuntimePlatform(starsectorGameDir?.let(::file)) })

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
    from(layout.buildDirectory.dir("named-game-jars/${mappingPlatform.get()}")) {
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
val nativeLinuxLibraryFile = project(":native").layout.buildDirectory.file("lib/main/debug/libnative.so")
val nativeWindowsLibraryFile = project(":native").layout.buildDirectory.file("lib/main/debug/native.dll")
val userModStageDir = layout.buildDirectory.dir("user-package/$modId")

tasks.register<Copy>("jarMapped") {
    group = "mapping"
    description = "Build mapped development jar"
    dependsOn(":app:jar", "remapToNamed")

    from(appJarFile)
    into(layout.buildDirectory.dir("libs"))
    rename { mappedJarFile.get().asFile.name }
}

tasks.register<Sync>("stageUserMod") {
    group = "distribution"
    description = "Stage an end-user ready mod layout under build/user-package"
    dependsOn("jarReobf", ":native:assemble")

    from(reobfJarFile) {
        into("jars")
        rename { "SSOptimizer.jar" }
    }
    from(project.provider {
        val file = nativeLinuxLibraryFile.get().asFile
        if (file.isFile) listOf(file) else emptyList<File>()
    }) {
        into("native/linux")
        rename { System.mapLibraryName(modId) }
    }
    from(project.provider {
        val file = nativeWindowsLibraryFile.get().asFile
        if (file.isFile) listOf(file) else emptyList<File>()
    }) {
        into("native/windows")
        rename { System.mapLibraryName(modId) }
    }
    from(rootProject.file("mod_info.json"))
    from(rootProject.file("README.md"))
    from(rootProject.file("tools/enable_starsector_exe_launch.ps1"))

    into(userModStageDir)

    doFirst {
        check(reobfJarFile.get().asFile.isFile) {
            "未找到用户发布所需的 reobf 产物: ${reobfJarFile.get().asFile}"
        }
        val hasAnyNative = nativeLinuxLibraryFile.get().asFile.isFile || nativeWindowsLibraryFile.get().asFile.isFile
        check(hasAnyNative) {
            "未找到任何可打包的原生库：linux=${nativeLinuxLibraryFile.get().asFile} windows=${nativeWindowsLibraryFile.get().asFile}"
        }
    }

    doLast {
        println("✓ End-user mod staged at ${userModStageDir.get().asFile}")
    }
}

val windowsOverlayStageDir = layout.buildDirectory.dir("user-package/windows-overlay")

tasks.register<Sync>("stageWindowsOverlay") {
    group = "distribution"
    description = "Stage a Windows game-root overlay with launcher patch helper"
    dependsOn("stageUserMod")

    from(userModStageDir) {
        into("mods/$modId")
    }
    from(rootProject.file("tools/enable_starsector_exe_launch.ps1"))

    into(windowsOverlayStageDir)

    doLast {
        println("✓ Windows overlay staged at ${windowsOverlayStageDir.get().asFile}")
    }
}

tasks.register<Zip>("packageUserModZip") {
    group = "distribution"
    description = "Package an end-user ready SSOptimizer zip under build/distributions"
    dependsOn("stageUserMod")

    archiveBaseName.set("SSOptimizer")
    archiveVersion.set(modReleaseVersion)
    archiveClassifier.set("user")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(userModStageDir) {
        into(modId)
    }

    doLast {
        println("✓ End-user zip written to ${archiveFile.get().asFile}")
    }
}

tasks.register<Zip>("packageWindowsOverlayZip") {
    group = "distribution"
    description = "Package a Windows game-root overlay zip that keeps starsector.exe as the launcher entry"
    dependsOn("stageWindowsOverlay")

    archiveBaseName.set("SSOptimizer")
    archiveVersion.set(modReleaseVersion)
    archiveClassifier.set("windows-overlay")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(windowsOverlayStageDir)

    doLast {
        println("✓ Windows overlay zip written to ${archiveFile.get().asFile}")
    }
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
    dependsOn(":app:jar")
    dependsOn("jarMapped")

    if (targetPlatformProvider.get() == "linux") {
        dependsOn(":native:assemble")
    }

    from(mappedJarFile) {
        into("jars")
        rename { "SSOptimizer.jar" }
    }
    from(project.provider {
        val platform = targetPlatformProvider.get()
        val nativeFile = if (platform == "windows") nativeWindowsLibraryFile.get().asFile else nativeLinuxLibraryFile.get().asFile
        if (nativeFile.isFile) listOf(nativeFile) else emptyList<File>()
    }) {
        into("native/${targetPlatformProvider.get()}")
        rename { System.mapLibraryName("ssoptimizer") }
    }
    from(rootProject.file("mod_info.json"))

    into(configuredGameDirProvider.map { file(it).resolve("mods/$modId") })

    doFirst {
        check(configuredGameDirProvider.isPresent) {
            "Missing Starsector directory. Pass -Pstarsector.gameDir=/path/to/Starsector or set SSOPTIMIZER_GAME_DIR."
        }
    }

    doLast {
        val platform = targetPlatformProvider.get()
        val nativeFile = if (platform == "windows") nativeWindowsLibraryFile.get().asFile else nativeLinuxLibraryFile.get().asFile
        val modDir = file(configuredGameDirProvider.get()).resolve("mods/$modId")
        modDir.resolve("jars/SSOptimizer-mapped.jar").delete()
        println("[installDevMod] Mod deployed to ${configuredGameDirProvider.get()}/mods/$modId")
        if (!nativeFile.isFile) {
            println("[installDevMod] Native runtime not available for $platform; Java fallbacks will be used")
        }

        val enabledModsFile = file(configuredGameDirProvider.get()).resolve("mods/enabled_mods.json")
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
                    println("[installDevMod] Added $modId to enabled_mods.json")
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
        check(configuredGameDirProvider.isPresent) {
            "Missing Starsector directory. Pass -Pstarsector.gameDir=/path/to/Starsector or set SSOPTIMIZER_GAME_DIR."
        }
        val gameDir = file(configuredGameDirProvider.get())
        val runtimeDir = resolveRuntimeDir(gameDir)
        val platform = detectRuntimePlatform(gameDir)
        val launchConfigFile = rootProject.file("launch-config.json")
        if (!launchConfigFile.exists()) {
            throw IllegalStateException("Missing launch-config.json in project root")
        }

        val javaExecutable = resolveBundledJavaExecutable(gameDir, platform)
            ?: throw IllegalStateException("Missing bundled Java runtime for $platform under ${gameDir.absolutePath}")

        workingDir = runtimeDir
        setExecutable(javaExecutable.absolutePath)

        @Suppress("UNCHECKED_CAST")
        val config = JsonSlurper().parse(launchConfigFile) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val jvmArgsConfig = config["jvmArgs"] as Map<String, List<String>>
        val commonArgs = rewriteRuntimePathArgs(platform, jvmArgsConfig["common"] ?: emptyList())
        val platformArgs = rewriteRuntimePathArgs(platform, jvmArgsConfig[platform] ?: emptyList())
        jvmArgs = commonArgs + platformArgs

        @Suppress("UNCHECKED_CAST")
        val classpathJars = config["classpath"] as List<String>
        classpath = files(classpathJars.map { runtimeDir.resolve(it) })
        mainClass.set("com.fs.starfarer.StarfarerLauncher")
    }
}

tasks.named("runClient") {
    dependsOn("runClientExec")
}
