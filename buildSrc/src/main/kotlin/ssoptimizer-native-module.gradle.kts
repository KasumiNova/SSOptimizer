/**
 * SSOptimizer native 子模块共享约定：平台探测、依赖解析、编译链接与运行时依赖收集。
 *
 * 由原 :native 单模块 build.gradle.kts 抽取而成，各功能域子模块
 * （render/loading/font/ime）通过 ssoNative { ... } 声明自己的依赖开关，
 * 产物名为 libssoptimizer_<moduleName>.so / ssoptimizer_<moduleName>.dll。
 *
 * 平台选择：-Pssoptimizer.native.target / -Pstarsector.platform 覆盖，默认随宿主。
 * Windows 交叉编译（Linux 宿主 → Windows 目标）由 compileWindowsCrossObjects /
 * linkWindowsCrossSharedLibrary 两个任务承担，产物落在 build/lib/main/release/。
 */
import org.gradle.language.cpp.tasks.CppCompile
import org.gradle.nativeplatform.Linkage
import org.gradle.nativeplatform.tasks.LinkSharedLibrary

import java.io.ByteArrayOutputStream
import java.io.File

plugins {
    `cpp-library`
}

val ssoNative = extensions.create("ssoNative", SsoNativeExtension::class.java)
ssoNative.moduleName.convention("module")
ssoNative.libpng.convention(false)
ssoNative.freetype.convention(false)
ssoNative.x11.convention(false)
ssoNative.windowsSystemLibs.convention(emptyList())
ssoNative.linuxSystemLibs.convention(emptyList())

// ---- 平台与工具链探测 ----
val hostOs = System.getProperty("os.name", "").lowercase()
val isWindowsHost = hostOs.contains("win")
val isLinuxHost = hostOs.contains("linux")
val requestedNativeTarget = providers.gradleProperty("ssoptimizer.native.target").orNull?.lowercase()
val requestedBuildPlatform = providers.gradleProperty("starsector.platform").orNull?.lowercase()
val targetOs = when {
    requestedNativeTarget == "windows" || requestedBuildPlatform == "windows" -> "windows"
    requestedNativeTarget == "linux" || requestedBuildPlatform == "linux" -> "linux"
    isWindowsHost -> "windows"
    else -> "linux"
}
val buildTargetIsWindows = targetOs == "windows"
val useWindowsCrossCompileTask = buildTargetIsWindows && !isWindowsHost && isLinuxHost

val javaHome = file(System.getProperty("java.home"))
val jdkHome = if (javaHome.resolve("include").exists()) {
    javaHome
} else {
    javaHome.parentFile ?: javaHome
}
val jniIncludeDir = jdkHome.resolve("include")
val processPath = providers.environmentVariable("PATH").orNull ?: ""

fun envOrProperty(propertyName: String, envName: String): String? {
    return providers.gradleProperty(propertyName).orNull
        ?: providers.environmentVariable(envName).orNull
}

fun pathWithPrependedBin(binDir: String?): String {
    if (binDir.isNullOrBlank()) {
        return processPath
    }
    return if (processPath.isBlank()) binDir else "$binDir:${processPath}"
}

fun resolveToolCommand(configuredCommand: String,
                       binDir: String?): String {
    val configuredFile = File(configuredCommand)
    if (configuredFile.isAbsolute) {
        return configuredFile.absolutePath
    }
    if (binDir.isNullOrBlank()) {
        return configuredCommand
    }
    return File(binDir, configuredCommand).absolutePath
}

fun runCommand(workingDir: File,
               environment: Map<String, String>,
               command: List<String>) {
    val process = ProcessBuilder(command)
        .directory(workingDir)
        .redirectErrorStream(true)
        .apply {
            this.environment().putAll(environment)
        }
        .start()

    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    if (output.isNotBlank()) {
        println(output)
    }
    check(exitCode == 0) {
        "命令执行失败($exitCode): ${command.joinToString(" ")}"
    }
}

fun resolveWindowsPackageRoot(packageName: String): File? {
    val explicitRoot = envOrProperty(
        "ssoptimizer.native.windows.${packageName}.root",
        "SSOPTIMIZER_NATIVE_${packageName.uppercase()}_ROOT"
    )
    if (!explicitRoot.isNullOrBlank()) {
        val root = file(explicitRoot)
        if (root.isDirectory) {
            return root
        }
    }

    val vcpkgRoot = envOrProperty("ssoptimizer.native.windows.vcpkgRoot", "VCPKG_ROOT")
    val defaultTriplet = if (isWindowsHost) "x64-windows" else "x64-mingw-static"
    val triplet = envOrProperty("ssoptimizer.native.windows.triplet", "VCPKG_DEFAULT_TRIPLET") ?: defaultTriplet
    if (vcpkgRoot.isNullOrBlank()) {
        return null
    }

    val installedRoot = file(vcpkgRoot).resolve("installed").resolve(triplet)
    return installedRoot.takeIf { it.isDirectory }
}

fun includeDirArgsWindows(root: File?, msvcStyle: Boolean): List<String> {
    if (root == null) {
        return emptyList()
    }
    val includeDir = root.resolve("include")
    val prefix = if (msvcStyle) "/I" else "-I"
    return if (includeDir.isDirectory) listOf("${prefix}${includeDir.absolutePath}") else emptyList()
}

fun resolveWindowsLibrary(libDirRoot: File?, vararg candidates: String): String? {
    if (libDirRoot == null) {
        return null
    }

    val candidateDirs = listOf(libDirRoot.resolve("lib"), libDirRoot.resolve("debug/lib"))
    for (dir in candidateDirs) {
        if (!dir.isDirectory) {
            continue
        }
        for (candidate in candidates) {
            val file = dir.resolve(candidate)
            if (file.isFile) {
                return file.absolutePath
            }
        }
    }
    return null
}

fun runPkgConfig(vararg args: String): List<String> {
    val output = ByteArrayOutputStream()
    val process = try {
        ProcessBuilder(listOf("pkg-config", *args))
            .redirectErrorStream(true)
            .start()
    } catch (_: Exception) {
        return emptyList()
    }

    process.inputStream.use { it.copyTo(output) }
    if (process.waitFor() != 0) {
        return emptyList()
    }

    return output.toString()
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
}

// pkg-config 探测（按模块开关决定是否进入编译/链接参数）
val libpngCompilerArgs = runPkgConfig("--cflags", "libpng")
val libpngLinkerArgs = runPkgConfig("--libs", "libpng")
val hasLibpng = libpngLinkerArgs.isNotEmpty()
val freetypeCompilerArgs = runPkgConfig("--cflags", "freetype2")
val freetypeLinkerArgs = runPkgConfig("--libs", "freetype2")
val hasFreetype = freetypeLinkerArgs.isNotEmpty()
val x11CompilerArgs = runPkgConfig("--cflags", "x11")
val x11LinkerArgs = runPkgConfig("--libs", "x11")
val hasX11 = x11LinkerArgs.isNotEmpty()

// Windows vcpkg 依赖解析
val windowsLibpngRoot = if (buildTargetIsWindows) resolveWindowsPackageRoot("libpng") else null
val windowsFreetypeRoot = if (buildTargetIsWindows) resolveWindowsPackageRoot("freetype") else null
val windowsDependencyRoot = windowsLibpngRoot ?: windowsFreetypeRoot
val windowsLibpngCompilerArgs = if (buildTargetIsWindows) includeDirArgsWindows(windowsLibpngRoot, isWindowsHost) else emptyList()
val windowsFreetypeCompilerArgs = if (buildTargetIsWindows) includeDirArgsWindows(windowsFreetypeRoot, isWindowsHost) else emptyList()
val windowsLibpngLinkerArgs = if (buildTargetIsWindows) {
    listOfNotNull(resolveWindowsLibrary(
        windowsLibpngRoot,
        "libpng16.lib",
        "png.lib",
        "libpng.lib",
        "libpng16.a",
        "libpng.a",
        "libpng16.dll.a",
        "libpng.dll.a"
    ))
} else {
    emptyList()
}
val windowsFreetypeLinkerArgs = if (buildTargetIsWindows) {
    listOfNotNull(resolveWindowsLibrary(
        windowsFreetypeRoot,
        "freetype.lib",
        "freetyped.lib",
        "libfreetype.a",
        "libfreetype.dll.a"
    ))
} else {
    emptyList()
}
val windowsZlibLinkerArgs = if (buildTargetIsWindows) {
    listOfNotNull(resolveWindowsLibrary(windowsDependencyRoot, "libzlib.a", "zlib.lib", "zlib1.lib"))
} else {
    emptyList()
}
val windowsBzip2LinkerArgs = if (buildTargetIsWindows) {
    listOfNotNull(resolveWindowsLibrary(windowsDependencyRoot, "libbz2.a", "bz2.lib", "libbz2.lib"))
} else {
    emptyList()
}
val windowsBrotliLinkerArgs = if (buildTargetIsWindows) {
    listOfNotNull(
        resolveWindowsLibrary(windowsDependencyRoot, "libbrotlicommon.a", "brotlicommon.lib"),
        resolveWindowsLibrary(windowsDependencyRoot, "libbrotlidec.a", "brotlidec.lib")
    )
} else {
    emptyList()
}
val hasWindowsLibpng = windowsLibpngLinkerArgs.isNotEmpty() || !windowsLibpngCompilerArgs.isEmpty()
val hasWindowsFreetype = windowsFreetypeLinkerArgs.isNotEmpty() || !windowsFreetypeCompilerArgs.isEmpty()

// MinGW 交叉工具链
val windowsToolchainBinDir = envOrProperty("ssoptimizer.native.windows.toolchainBinDir", "SSOPTIMIZER_NATIVE_WINDOWS_TOOLCHAIN_BIN")
val windowsCrossPath = pathWithPrependedBin(windowsToolchainBinDir)
val windowsCrossCc = resolveToolCommand(envOrProperty("ssoptimizer.native.windows.cc", "CC") ?: "x86_64-w64-mingw32-gcc", windowsToolchainBinDir)
val windowsCrossCxx = resolveToolCommand(envOrProperty("ssoptimizer.native.windows.cxx", "CXX") ?: "x86_64-w64-mingw32-g++", windowsToolchainBinDir)
val windowsCrossRc = resolveToolCommand(envOrProperty("ssoptimizer.native.windows.rc", "RC") ?: "x86_64-w64-mingw32-windres", windowsToolchainBinDir)
val windowsJniPlatformIncludeOverride = envOrProperty(
    "ssoptimizer.native.windows.jniPlatformIncludeDir",
    "SSOPTIMIZER_NATIVE_WINDOWS_JNI_PLATFORM_INCLUDE"
)
val jniPlatformIncludeDir = when {
    buildTargetIsWindows -> windowsJniPlatformIncludeOverride?.let(::file) ?: jniIncludeDir.resolve("win32")
    else -> jniIncludeDir.resolve("linux")
}

// 产物路径：文件名跟随模块 baseName（libssoptimizer_<module>.so / ssoptimizer_<module>.dll）
val windowsCrossOutputFile = layout.buildDirectory.file(
    providers.provider { "lib/main/release/ssoptimizer_${ssoNative.moduleName.get()}.dll" }
)
val windowsCrossObjectDir = layout.buildDirectory.dir("tmp/windows-cross/obj")
val moduleHeaderDirs = listOf(file("src/main/headers")).filter { it.exists() }
// JNI 生成头统一位于根级共享目录（core/render/loading/font/ime 的 compileJava -h 产出）
val generatedHeaderDir = rootProject.layout.projectDirectory.dir("native-headers/generated")
val nativeHeaderDirs = moduleHeaderDirs + listOf(generatedHeaderDir.asFile)
val windowsCrossSources = fileTree("src/main/cpp") {
    include("**/*.cpp")
}.files.sortedBy { it.absolutePath }

/**
 * 收集需要随本模块 DLL 一起部署的运行时依赖 DLL 列表（按模块开关裁剪）。
 * libpng → libpng16.dll + zlib1.dll；freetype → freetype.dll + zlib1.dll + bz2.dll + brotli 系列。
 */
fun resolveWindowsRuntimeDlls(): List<File> {
    if (!buildTargetIsWindows) return emptyList()
    val found = mutableSetOf<File>()
    if (ssoNative.libpng.getOrElse(false)) {
        windowsLibpngRoot?.let { root ->
            val binDir = root.resolve("bin")
            if (binDir.isDirectory) {
                for (name in listOf("libpng16.dll", "zlib1.dll")) {
                    val candidate = binDir.resolve(name)
                    if (candidate.isFile) found.add(candidate)
                }
            }
        }
    }
    if (ssoNative.freetype.getOrElse(false)) {
        windowsFreetypeRoot?.let { root ->
            val binDir = root.resolve("bin")
            if (binDir.isDirectory) {
                for (name in listOf("freetype.dll", "zlib1.dll", "bz2.dll", "brotlidec.dll", "brotlicommon.dll")) {
                    val candidate = binDir.resolve(name)
                    if (candidate.isFile) found.add(candidate)
                }
            }
        }
    }
    return found.toList()
}

/**
 * 收集需要随本模块 .so 一起部署的运行时共享库（从 pkg-config --libs 输出目录解析，按模块开关裁剪）。
 * 与 libpng/freetype 链接的模块各自收集；X11 为系统库，一般无需随模组部署。
 */
fun resolveLinuxRuntimeSharedLibs(): List<File> {
    if (buildTargetIsWindows) return emptyList()
    val libDirs = mutableSetOf<File>()
    val allLinkerArgs = mutableListOf<String>()
    if (ssoNative.libpng.getOrElse(false)) allLinkerArgs.addAll(libpngLinkerArgs)
    if (ssoNative.freetype.getOrElse(false)) allLinkerArgs.addAll(freetypeLinkerArgs)
    for (arg in allLinkerArgs) {
        if (arg.startsWith("-L")) {
            val dir = file(arg.removePrefix("-L"))
            if (dir.isDirectory) libDirs.add(dir)
        }
    }
    val libNames = allLinkerArgs.filter { it.startsWith("-l") }.map { it.removePrefix("-l") }.distinct()
    val found = mutableSetOf<File>()
    for (dir in libDirs) {
        for (name in libNames) {
            dir.listFiles()?.filter { f ->
                f.name == "lib${name}.so" || f.name.startsWith("lib${name}.so.")
            }?.forEach { found.add(it) }
        }
    }
    return found.toList()
}

// 运行时依赖收集依赖 ssoNative 开关，须在模块脚本配置完成后求值
afterEvaluate {
    extra["windowsRuntimeDlls"] = resolveWindowsRuntimeDlls()
    extra["linuxRuntimeSharedLibs"] = resolveLinuxRuntimeSharedLibs()
}

library {
    targetMachines.set(listOf(if (buildTargetIsWindows) machines.windows.x86_64 else machines.linux.x86_64))
    linkage.set(listOf(Linkage.SHARED))
    baseName.set(providers.provider { "ssoptimizer_${ssoNative.moduleName.get()}" })

    // 模块自身头（render 的 glad/KHR）+ 根级共享 JNI 生成头目录
    privateHeaders.from(nativeHeaderDirs)
}

tasks.withType<CppCompile>().configureEach {
    dependsOn(":modules:internal:sso-app:compileJava")
    val enableLibpng = ssoNative.libpng.get()
    val enableFreetype = ssoNative.freetype.get()
    val enableX11 = ssoNative.x11.get()
    if (buildTargetIsWindows && isWindowsHost) {
        compilerArgs.addAll(
            listOf(
                "/std:c++20",
                "/O2",
                "/EHsc",
                "/permissive-",
                "/DWIN32_LEAN_AND_MEAN",
                "/DNOMINMAX",
                "/D_CRT_SECURE_NO_WARNINGS",
                "/I${jniIncludeDir.absolutePath}",
                "/I${jniPlatformIncludeDir.absolutePath}"
            )
        )
        if (enableLibpng) {
            compilerArgs.addAll(windowsLibpngCompilerArgs)
            if (hasWindowsLibpng) {
                compilerArgs.add("/DSSOPTIMIZER_HAVE_LIBPNG=1")
            }
        }
        if (enableFreetype) {
            compilerArgs.addAll(windowsFreetypeCompilerArgs)
            if (hasWindowsFreetype) {
                compilerArgs.add("/DSSOPTIMIZER_HAVE_FREETYPE=1")
            }
        }
    } else if (buildTargetIsWindows) {
        compilerArgs.addAll(
            listOf(
                "-std=c++20",
                "-O3",
                "-fno-math-errno",
                "-fno-trapping-math",
                "-DWIN32_LEAN_AND_MEAN",
                "-DNOMINMAX",
                "-D_CRT_SECURE_NO_WARNINGS",
                "-I${jniIncludeDir.absolutePath}",
                "-I${jniPlatformIncludeDir.absolutePath}"
            )
        )
        if (enableLibpng) {
            compilerArgs.addAll(windowsLibpngCompilerArgs)
            if (hasWindowsLibpng) {
                compilerArgs.add("-DSSOPTIMIZER_HAVE_LIBPNG=1")
            }
        }
        if (enableFreetype) {
            compilerArgs.addAll(windowsFreetypeCompilerArgs)
            if (hasWindowsFreetype) {
                compilerArgs.add("-DSSOPTIMIZER_HAVE_FREETYPE=1")
            }
        }
    } else {
        compilerArgs.addAll(
            listOf(
                "-std=c++20",
                "-O3",
                "-fno-math-errno",
                "-fno-trapping-math",
                "-fPIC",
                "-I${jniIncludeDir.absolutePath}",
                "-I${jniPlatformIncludeDir.absolutePath}"
            )
        )
        if (enableLibpng) {
            compilerArgs.addAll(libpngCompilerArgs)
            if (hasLibpng) {
                compilerArgs.add("-DSSOPTIMIZER_HAVE_LIBPNG=1")
            }
        }
        if (enableFreetype) {
            compilerArgs.addAll(freetypeCompilerArgs)
            if (hasFreetype) {
                compilerArgs.add("-DSSOPTIMIZER_HAVE_FREETYPE=1")
            }
        }
        if (enableX11) {
            compilerArgs.addAll(x11CompilerArgs)
            if (hasX11) {
                compilerArgs.add("-DSSOPTIMIZER_HAVE_X11=1")
            }
        }
    }
}

tasks.withType<LinkSharedLibrary>().configureEach {
    val enableLibpng = ssoNative.libpng.get()
    val enableFreetype = ssoNative.freetype.get()
    val enableX11 = ssoNative.x11.get()
    val windowsSystemLibs = ssoNative.windowsSystemLibs.getOrElse(emptyList())
    val linuxSystemLibs = ssoNative.linuxSystemLibs.getOrElse(emptyList())
    if (buildTargetIsWindows && isWindowsHost) {
        linkerArgs.addAll(windowsSystemLibs.map { "${it}.lib" })
        if (enableLibpng) linkerArgs.addAll(windowsLibpngLinkerArgs)
        if (enableFreetype) linkerArgs.addAll(windowsFreetypeLinkerArgs)
    } else if (buildTargetIsWindows) {
        linkerArgs.addAll(windowsSystemLibs.map { "-l$it" })
        if (enableLibpng) linkerArgs.addAll(windowsLibpngLinkerArgs)
        if (enableFreetype) linkerArgs.addAll(windowsFreetypeLinkerArgs)
    } else {
        linkerArgs.addAll(linuxSystemLibs.map { "-l$it" })
        if (enableLibpng) linkerArgs.addAll(libpngLinkerArgs)
        if (enableFreetype) linkerArgs.addAll(freetypeLinkerArgs)
        if (enableX11) linkerArgs.addAll(x11LinkerArgs)
        // RPATH 指向 $ORIGIN：随模组部署的 .so 依赖运行时按同目录解析
        linkerArgs.addAll(listOf("-Wl,-rpath,\$ORIGIN"))
    }
}

if (useWindowsCrossCompileTask) {
    val compileWindowsCrossObjects = tasks.register("compileWindowsCrossObjects") {
        group = "build"
        description = "Compile Windows native objects on Linux using a MinGW-compatible cross toolchain"
        dependsOn(":modules:internal:sso-app:compileJava")

        inputs.files(windowsCrossSources)
        inputs.dir(jniIncludeDir)
        inputs.dir(jniPlatformIncludeDir)
        outputs.dir(windowsCrossObjectDir)

        doFirst {
            check(windowsCrossSources.isNotEmpty()) {
                "未找到任何 native C++ 源文件。"
            }
            if (ssoNative.libpng.get()) {
                check(hasWindowsLibpng) {
                    "未找到 Windows libpng 依赖，请设置 VCPKG_ROOT 或 -Pssoptimizer.native.windows.libpng.root。"
                }
            }
            if (ssoNative.freetype.get()) {
                check(hasWindowsFreetype) {
                    "未找到 Windows freetype 依赖，请设置 VCPKG_ROOT 或 -Pssoptimizer.native.windows.freetype.root。"
                }
            }
            windowsCrossObjectDir.get().asFile.mkdirs()
        }

        doLast {
            val objectDir = windowsCrossObjectDir.get().asFile
            val workingDir = project.projectDir
            val environment = mapOf(
                "PATH" to windowsCrossPath,
                "CC" to windowsCrossCc,
                "CXX" to windowsCrossCxx,
                "RC" to windowsCrossRc
            )
            val commonArgs = mutableListOf(
                "-std=c++20",
                "-O3",
                "-fno-math-errno",
                "-fno-trapping-math",
                "-DWIN32_LEAN_AND_MEAN",
                "-DNOMINMAX",
                "-D_CRT_SECURE_NO_WARNINGS",
                "-I${jniIncludeDir.absolutePath}",
                "-I${jniPlatformIncludeDir.absolutePath}"
            )
            nativeHeaderDirs.forEach { headerDir ->
                commonArgs.add("-I${headerDir.absolutePath}")
            }
            if (ssoNative.libpng.get()) {
                commonArgs.addAll(windowsLibpngCompilerArgs)
                if (hasWindowsLibpng) {
                    commonArgs.add("-DSSOPTIMIZER_HAVE_LIBPNG=1")
                }
            }
            if (ssoNative.freetype.get()) {
                commonArgs.addAll(windowsFreetypeCompilerArgs)
                if (hasWindowsFreetype) {
                    commonArgs.add("-DSSOPTIMIZER_HAVE_FREETYPE=1")
                }
            }

            windowsCrossSources.forEach { source ->
                val objectFile = objectDir.resolve(source.nameWithoutExtension + ".o")
                runCommand(workingDir, environment,
                    listOf(windowsCrossCxx) + commonArgs + listOf("-c", source.absolutePath, "-o", objectFile.absolutePath))
            }
        }
    }

    tasks.register("linkWindowsCrossSharedLibrary") {
        group = "build"
        description = "Link the Windows native shared library on Linux using a MinGW-compatible cross toolchain"
        dependsOn(compileWindowsCrossObjects)

        inputs.dir(windowsCrossObjectDir)
        outputs.file(windowsCrossOutputFile)

        doFirst {
            val outputFile = windowsCrossOutputFile.get().asFile
            outputFile.parentFile.mkdirs()
        }

        doLast {
            val workingDir = project.projectDir
            val environment = mapOf(
                "PATH" to windowsCrossPath,
                "CC" to windowsCrossCc,
                "CXX" to windowsCrossCxx,
                "RC" to windowsCrossRc
            )
            val outputFile = windowsCrossOutputFile.get().asFile

            val objectFiles = windowsCrossSources.map { source ->
                windowsCrossObjectDir.get().asFile.resolve(source.nameWithoutExtension + ".o").absolutePath
            }

            val argsList = mutableListOf(
                "-shared",
                "-static-libgcc",
                "-static-libstdc++",
                "-o",
                outputFile.absolutePath
            )
            argsList.addAll(objectFiles)
            argsList.addAll(ssoNative.windowsSystemLibs.getOrElse(emptyList()).map { "-l$it" })
            if (ssoNative.libpng.get()) {
                argsList.addAll(windowsLibpngLinkerArgs)
                argsList.addAll(windowsZlibLinkerArgs)
            }
            if (ssoNative.freetype.get()) {
                argsList.addAll(windowsFreetypeLinkerArgs)
                argsList.addAll(windowsBzip2LinkerArgs)
                argsList.addAll(windowsBrotliLinkerArgs)
            }

            runCommand(workingDir, environment, listOf(windowsCrossCxx) + argsList)
        }
    }

    tasks.named("assemble") {
        dependsOn("linkWindowsCrossSharedLibrary")
    }

    tasks.matching { it.name == "assembleRelease" }.configureEach {
        dependsOn("linkWindowsCrossSharedLibrary")
    }

    tasks.named("build") {
        dependsOn("linkWindowsCrossSharedLibrary")
    }
}
