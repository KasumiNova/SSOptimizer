import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip

plugins {
    base
}

group = "github.kasuminova.ssoptimizer"
version = "0.1.10-SNAPSHOT"

// 模组元数据的唯一事实源在 :app 的 starsector {} DSL；此处仅保留打包所需的常量
val modId = "ssoptimizer"
val modReleaseVersion = project.version.toString()

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
    dependsOn(":modules:internal:sso-app:docsTest")
}

tasks.register("bootstrapDev") {
    group = "dev workflow"
    description = "Initialize and verify development environment"
    dependsOn("doctor", "docsCheck", ":modules:internal:sso-app:genIdeaRuns", ":modules:internal:sso-app:decompileDependencies")
    doLast {
        println("✓ Development environment bootstrap complete")
    }
}

tasks.register("qualityGateLocal") {
    group = "dev workflow"
    description = "Run all quality gates (docs + tests + diagnostics)"
    dependsOn("docsCheck", ":modules:internal:sso-app:test", "doctor")
    doLast {
        println("✓ All local quality gates passed")
    }
}

tasks.register("devCycle") {
    group = "dev workflow"
    description = "Daily development cycle: quality gates + build"
    dependsOn("qualityGateLocal", ":modules:internal:sso-app:classes")
    doLast {
        println("✓ Dev cycle complete — ready to run")
    }
}

val appJarFile = project(":modules:internal:sso-app").layout.buildDirectory.file("libs/SSOptimizer.jar")

// 功能域 native 子模块：产物 libssoptimizer_<module>.so / ssoptimizer_<module>.dll，
// runtime deps 按模块各自收集；texcompress 挂在 sso-loading 域下（独立库，懒加载）
val nativeProjectPaths = mapOf(
    "render" to ":modules:internal:sso-render:native-render",
    "loading" to ":modules:internal:sso-loading:native-loading",
    "font" to ":modules:internal:sso-font:native-font",
    "ime" to ":modules:internal:sso-ime:native-ime",
    "texcompress" to ":modules:internal:sso-loading:native-texcompress",
)
val nativeModules = nativeProjectPaths.keys.toList()

fun nativeProject(module: String) = project(nativeProjectPaths.getValue(module))

fun nativeLibraryFile(module: String, windows: Boolean) = nativeProject(module).layout.buildDirectory.file(
    "lib/main/release/" + if (windows) "ssoptimizer_$module.dll" else "libssoptimizer_$module.so"
)

val nativeLinuxLibraryFiles = nativeModules.map { nativeLibraryFile(it, false) }
val nativeWindowsLibraryFiles = nativeModules.map { nativeLibraryFile(it, true) }
val nativeWindowsRuntimeDlls: List<File> by lazy {
    nativeModules.flatMap { module ->
        @Suppress("UNCHECKED_CAST")
        (nativeProject(module).extra["windowsRuntimeDlls"] as? List<File>) ?: emptyList()
    }.distinct()
}
val nativeLinuxRuntimeSharedLibs: List<File> by lazy {
    nativeModules.flatMap { module ->
        @Suppress("UNCHECKED_CAST")
        (nativeProject(module).extra["linuxRuntimeSharedLibs"] as? List<File>) ?: emptyList()
    }.distinct()
}
val packagedFontTtfDir = rootProject.file("game-fonts/ttf")
val log4jConfigFile = rootProject.file("log4j.properties")
val userModStageDir = layout.buildDirectory.dir("user-package/$modId")

tasks.register<Sync>("stageUserMod") {
    group = "distribution"
    description = "Stage an end-user ready mod layout under build/user-package"
    dependsOn(":modules:internal:sso-app:modProduction")
    nativeModules.forEach { module ->
        dependsOn("${nativeProjectPaths.getValue(module)}:assembleRelease")
    }

    from(appJarFile) {
        into("jars")
    }
    // 四个功能域原生库平铺进 native/<platform>/（产物名即部署名，不再 rename）
    from(project.provider {
        nativeLinuxLibraryFiles.map { it.get().asFile }.filter { it.isFile }
    }) {
        into("native/linux")
    }
    from(project.provider { nativeLinuxRuntimeSharedLibs.filter { it.isFile } }) {
        into("native/linux")
    }
    from(project.provider {
        nativeWindowsLibraryFiles.map { it.get().asFile }.filter { it.isFile }
    }) {
        into("native/windows")
    }
    from(project.provider { nativeWindowsRuntimeDlls.filter { it.isFile } }) {
        into("native/windows")
    }
    from(packagedFontTtfDir) {
        into("fonts")
    }
    // 元数据由 SDG 生成（mod_info.json + nanoforge.mod.toml，与部署产物同源）
    from(project(":modules:internal:sso-app").layout.buildDirectory.dir("mod_production")) {
        include("mod_info.json", "nanoforge.mod.toml")
    }
    from(rootProject.file("README.md"))

    into(userModStageDir)

    doFirst {
        check(appJarFile.get().asFile.isFile) {
            "未找到用户发布所需的 app 产物: ${appJarFile.get().asFile}"
        }
        // 主库（render）存在即视为具备原生运行时；其余模块可缺失（Java 回退）
        val renderLinux = nativeLinuxLibraryFiles.first().get().asFile
        val renderWindows = nativeWindowsLibraryFiles.first().get().asFile
        check(renderLinux.isFile || renderWindows.isFile) {
            "未找到主原生库（render）：linux=$renderLinux windows=$renderWindows"
        }
    }

    doLast {
        println("✓ End-user mod staged at ${userModStageDir.get().asFile}")
    }
}

val linuxOverlayStageDir = layout.buildDirectory.dir("user-package/linux-overlay")
val windowsOverlayStageDir = layout.buildDirectory.dir("user-package/windows-overlay")
val installBundleStageDir = layout.buildDirectory.dir("user-package/install-bundle")

tasks.register<Sync>("stageLinuxOverlay") {
    group = "distribution"
    description = "Stage a Linux game-root overlay containing mods/ssoptimizer and the NanoForge coremod"
    dependsOn("stageUserMod")

    from(userModStageDir) {
        into("mods/$modId")
        exclude("native/windows/**")
    }
    // NanoForge coremod 入口：游戏根 mods/coremods/ 由 NanoForge 发现装配
    from(appJarFile) {
        into("mods/coremods")
    }
    from(log4jConfigFile)

    into(linuxOverlayStageDir)

    doLast {
        println("✓ Linux overlay staged at ${linuxOverlayStageDir.get().asFile}")
    }
}

tasks.register<Sync>("stageWindowsOverlay") {
    group = "distribution"
    description = "Stage a Windows game-root overlay with mods/ssoptimizer and the NanoForge coremod"
    dependsOn("stageUserMod")

    from(userModStageDir) {
        into("mods/$modId")
        exclude("native/linux/**")
    }
    // NanoForge coremod 入口：游戏根 mods/coremods/ 由 NanoForge 发现装配
    from(appJarFile) {
        into("mods/coremods")
    }
    from(log4jConfigFile) {
        into("starsector-core")
    }

    into(windowsOverlayStageDir)

    doLast {
        println("✓ Windows overlay staged at ${windowsOverlayStageDir.get().asFile}")
    }
}

tasks.register<Sync>("stageInstallBundle") {
    group = "distribution"
    description = "Stage a unified install bundle containing both Linux and Windows overlays"
    dependsOn("stageLinuxOverlay", "stageWindowsOverlay")

    from(linuxOverlayStageDir) {
        into("linux")
    }
    from(windowsOverlayStageDir) {
        into("windows")
    }

    into(installBundleStageDir)

    doLast {
        println("✓ Unified install bundle staged at ${installBundleStageDir.get().asFile}")
    }
}

tasks.register<Zip>("packageUserModZip") {
    group = "distribution"
    description = "Package a unified Linux + Windows install bundle under build/distributions"
    dependsOn("stageInstallBundle")

    archiveBaseName.set("SSOptimizer")
    archiveVersion.set(modReleaseVersion)
    archiveClassifier.set("")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(installBundleStageDir)

    doLast {
        println("✓ End-user zip written to ${archiveFile.get().asFile}")
    }
}

tasks.register("packageInstallBundleZip") {
    group = "distribution"
    description = "Compatibility alias for the unified install bundle zip"
    dependsOn("packageUserModZip")
}

tasks.register<Zip>("packageLinuxOverlayZip") {
    group = "distribution"
    description = "Package a Linux game-root overlay zip containing mods/ssoptimizer and the NanoForge coremod"
    dependsOn("stageLinuxOverlay")

    archiveBaseName.set("SSOptimizer")
    archiveVersion.set(modReleaseVersion)
    archiveClassifier.set("linux")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(linuxOverlayStageDir)

    doFirst {
        check(nativeLinuxLibraryFiles.first().get().asFile.isFile) {
            "未找到 Linux 原生库: ${nativeLinuxLibraryFiles.first().get().asFile}"
        }
    }

    doLast {
        println("✓ Linux overlay zip written to ${archiveFile.get().asFile}")
    }
}

tasks.register<Zip>("packageWindowsOverlayZip") {
    group = "distribution"
    description = "Package a Windows game-root overlay zip that keeps starsector.exe as the launcher entry"
    dependsOn("stageWindowsOverlay")

    archiveBaseName.set("SSOptimizer")
    archiveVersion.set(modReleaseVersion)
    archiveClassifier.set("windows")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(windowsOverlayStageDir)

    doFirst {
        check(nativeWindowsLibraryFiles.first().get().asFile.isFile) {
            "未找到 Windows 原生库: ${nativeWindowsLibraryFiles.first().get().asFile}"
        }
    }

    doLast {
        println("✓ Windows overlay zip written to ${archiveFile.get().asFile}")
    }
}

tasks.register("packageReleaseZips") {
    group = "distribution"
    description = "Build both Linux and Windows release zips"
    dependsOn("packageLinuxOverlayZip", "packageWindowsOverlayZip")
}

tasks.register("runClient") {
    group = "dev workflow"
    description = "Launch the Starsector client with the deployed mod"
    dependsOn(":modules:internal:sso-app:runGame")
}

tasks.register<Copy>("installNativeRuntime") {
    group = "dev workflow"
    description = "Deploy native runtime libraries into the deployed mod directory"
    if (targetPlatformProvider.get() == "linux") {
        nativeModules.forEach { module ->
            dependsOn("${nativeProjectPaths.getValue(module)}:assembleRelease")
        }
    }

    from(project.provider {
        val platform = targetPlatformProvider.get()
        val libraryFiles = if (platform == "windows") nativeWindowsLibraryFiles else nativeLinuxLibraryFiles
        libraryFiles.map { it.get().asFile }.filter { it.isFile }
    }) {
        into("native/${targetPlatformProvider.get()}")
    }
    from(project.provider {
        val platform = targetPlatformProvider.get()
        val deps = if (platform == "windows") nativeWindowsRuntimeDlls else nativeLinuxRuntimeSharedLibs
        deps.filter { it.isFile }
    }) {
        into("native/${targetPlatformProvider.get()}")
    }

    into(configuredGameDirProvider.map { file(it).resolve("mods/$modId") })

    // SDG deployMod 的 Sync 会清掉 native/，必须在其之后落位
    mustRunAfter(":modules:internal:sso-app:deployMod")

    doFirst {
        check(configuredGameDirProvider.isPresent) {
            "Missing Starsector directory. Pass -Pstarsector.gameDir=/path/to/Starsector or set SSOPTIMIZER_GAME_DIR."
        }
    }

    doLast {
        val platform = targetPlatformProvider.get()
        val libraryFiles = if (platform == "windows") nativeWindowsLibraryFiles else nativeLinuxLibraryFiles
        println("[installNativeRuntime] Native runtime deployed to ${configuredGameDirProvider.get()}/mods/$modId")
        if (libraryFiles.none { it.get().asFile.isFile }) {
            println("[installNativeRuntime] Native runtime not available for $platform; Java fallbacks will be used")
        }
    }
}

tasks.register<Copy>("installFontResources") {
    group = "dev workflow"
    description = "Deploy TTF font resources into the deployed mod directory (runtime font generator source)"

    from(packagedFontTtfDir) {
        into("fonts")
    }

    into(configuredGameDirProvider.map { file(it).resolve("mods/$modId") })

    // SDG deployMod 的 Sync 会清掉 fonts/，必须在其之后落位
    mustRunAfter(":modules:internal:sso-app:deployMod")

    doFirst {
        check(configuredGameDirProvider.isPresent) {
            "Missing Starsector directory. Pass -Pstarsector.gameDir=/path/to/Starsector or set SSOPTIMIZER_GAME_DIR."
        }
        check(packagedFontTtfDir.isDirectory) {
            "未找到字体资源目录: $packagedFontTtfDir"
        }
    }

    doLast {
        println("[installFontResources] Font resources deployed to ${configuredGameDirProvider.get()}/mods/$modId/fonts")
    }
}

tasks.register("deployMod") {
    group = "dev workflow"
    description = "Deploy mod metadata/jars (SDG :modules:internal:sso-app:deployMod) plus native runtime and font resources"
    dependsOn(":modules:internal:sso-app:deployMod", "installNativeRuntime", "installFontResources")
}

