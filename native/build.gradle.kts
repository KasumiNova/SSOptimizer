import org.gradle.language.cpp.tasks.CppCompile
import org.gradle.nativeplatform.tasks.LinkSharedLibrary
import org.gradle.nativeplatform.Linkage

import java.io.ByteArrayOutputStream
import java.io.File

plugins {
    `cpp-library`
}

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

val libpngCompilerArgs = runPkgConfig("--cflags", "libpng")
val libpngLinkerArgs = runPkgConfig("--libs", "libpng")
val hasLibpng = libpngLinkerArgs.isNotEmpty()
val freetypeCompilerArgs = runPkgConfig("--cflags", "freetype2")
val freetypeLinkerArgs = runPkgConfig("--libs", "freetype2")
val hasFreetype = freetypeLinkerArgs.isNotEmpty()
val x11CompilerArgs = runPkgConfig("--cflags", "x11")
val x11LinkerArgs = runPkgConfig("--libs", "x11")
val hasX11 = x11LinkerArgs.isNotEmpty()

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
val windowsCrossOutputFile = layout.buildDirectory.file("lib/main/debug/native.dll")
val windowsCrossObjectDir = layout.buildDirectory.dir("tmp/windows-cross/obj")
val nativeHeaderDirs = listOf(
    file("src/main/headers"),
    file("src/main/headers/generated")
).filter { it.exists() }
val windowsCrossSources = fileTree("src/main/cpp") {
    include("**/*.cpp")
}.files.sortedBy { it.absolutePath }

/**
 * 收集需要随主 DLL 一起部署的运行时依赖 DLL/SO 文件列表。
 * Windows: vcpkg bin 目录下的 libpng16.dll / freetype.dll / zlib1.dll / bz2.dll / brotlidec.dll / brotlicommon.dll
 * Linux: pkg-config --libs 解析出的 .so 所在目录下的实际库文件
 */
fun resolveWindowsRuntimeDlls(): List<File> {
    if (!buildTargetIsWindows) return emptyList()
    val roots = listOfNotNull(windowsLibpngRoot, windowsFreetypeRoot).distinct()
    val dllNames = listOf(
        "libpng16.dll",
        "freetype.dll",
        "zlib1.dll",
        "bz2.dll",
        "brotlidec.dll", "brotlicommon.dll"
    )
    val found = mutableSetOf<File>()
    for (root in roots) {
        val dir = root.resolve("bin")
        if (dir.isDirectory) {
            for (name in dllNames) {
                val candidate = dir.resolve(name)
                if (candidate.isFile) {
                    found.add(candidate)
                }
            }
        }
    }
    return found.toList()
}

fun resolveLinuxRuntimeSharedLibs(): List<File> {
    if (buildTargetIsWindows) return emptyList()
    // On Linux, libpng and freetype are typically system libraries found via LD_LIBRARY_PATH or RPATH.
    // We collect the .so files from pkg-config --libs output directories.
    val libDirs = mutableSetOf<File>()
    val allLinkerArgs = libpngLinkerArgs + freetypeLinkerArgs
    for (arg in allLinkerArgs) {
        if (arg.startsWith("-L")) {
            val dir = file(arg.removePrefix("-L"))
            if (dir.isDirectory) libDirs.add(dir)
        }
    }
    // Extract library names from -l flags and resolve from lib dirs
    val libNames = allLinkerArgs.filter { it.startsWith("-l") }.map { it.removePrefix("-l") }.distinct()
    val found = mutableSetOf<File>()
    for (dir in libDirs) {
        for (name in libNames) {
            // Look for libXXX.so and libXXX.so.* (versioned)
            dir.listFiles()?.filter { f ->
                f.name == "lib${name}.so" || f.name.startsWith("lib${name}.so.")
            }?.forEach { found.add(it) }
        }
    }
    return found.toList()
}

val windowsRuntimeDlls: List<File> = resolveWindowsRuntimeDlls()
val linuxRuntimeSharedLibs: List<File> = resolveLinuxRuntimeSharedLibs()

// Expose to root project for packaging
extra["windowsRuntimeDlls"] = windowsRuntimeDlls
extra["linuxRuntimeSharedLibs"] = linuxRuntimeSharedLibs

library {
    targetMachines.set(listOf(if (buildTargetIsWindows) machines.windows.x86_64 else machines.linux.x86_64))
    linkage.set(listOf(Linkage.SHARED))

    // 预留 generated 目录给 Java -h 产出的 JNI 头文件
    privateHeaders.from(file("src/main/headers"), file("src/main/headers/generated"))
}

tasks.withType<CppCompile>().configureEach {
    dependsOn(":app:compileJava")
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
        compilerArgs.addAll(windowsLibpngCompilerArgs)
        compilerArgs.addAll(windowsFreetypeCompilerArgs)
        if (hasWindowsLibpng) {
            compilerArgs.add("/DSSOPTIMIZER_HAVE_LIBPNG=1")
        }
        if (hasWindowsFreetype) {
            compilerArgs.add("/DSSOPTIMIZER_HAVE_FREETYPE=1")
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
        compilerArgs.addAll(windowsLibpngCompilerArgs)
        compilerArgs.addAll(windowsFreetypeCompilerArgs)
        if (hasWindowsLibpng) {
            compilerArgs.add("-DSSOPTIMIZER_HAVE_LIBPNG=1")
        }
        if (hasWindowsFreetype) {
            compilerArgs.add("-DSSOPTIMIZER_HAVE_FREETYPE=1")
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
        compilerArgs.addAll(libpngCompilerArgs)
        compilerArgs.addAll(freetypeCompilerArgs)
        compilerArgs.addAll(x11CompilerArgs)
        if (hasLibpng) {
            compilerArgs.add("-DSSOPTIMIZER_HAVE_LIBPNG=1")
        }
        if (hasFreetype) {
            compilerArgs.add("-DSSOPTIMIZER_HAVE_FREETYPE=1")
        }
        if (hasX11) {
            compilerArgs.add("-DSSOPTIMIZER_HAVE_X11=1")
        }
    }
}

tasks.withType<LinkSharedLibrary>().configureEach {
    if (buildTargetIsWindows && isWindowsHost) {
        linkerArgs.addAll(listOf("opengl32.lib", "user32.lib", "imm32.lib"))
        linkerArgs.addAll(windowsLibpngLinkerArgs)
        linkerArgs.addAll(windowsFreetypeLinkerArgs)
    } else if (buildTargetIsWindows) {
        linkerArgs.addAll(listOf("-lopengl32", "-luser32", "-limm32"))
        linkerArgs.addAll(windowsLibpngLinkerArgs)
        linkerArgs.addAll(windowsFreetypeLinkerArgs)
    } else {
        linkerArgs.addAll(listOf("-lGL"))
        linkerArgs.addAll(libpngLinkerArgs)
        linkerArgs.addAll(freetypeLinkerArgs)
        linkerArgs.addAll(x11LinkerArgs)
        // Set RPATH to $ORIGIN so that co-deployed .so dependencies are found at runtime
        linkerArgs.addAll(listOf("-Wl,-rpath,\$ORIGIN"))
    }
}

if (useWindowsCrossCompileTask) {
    val compileWindowsCrossObjects = tasks.register("compileWindowsCrossObjects") {
        group = "build"
        description = "Compile Windows native objects on Linux using a MinGW-compatible cross toolchain"
        dependsOn(":app:compileJava")

        inputs.files(windowsCrossSources)
        inputs.dir(jniIncludeDir)
        inputs.dir(jniPlatformIncludeDir)
        outputs.dir(windowsCrossObjectDir)

        doFirst {
            check(windowsCrossSources.isNotEmpty()) {
                "未找到任何 native C++ 源文件。"
            }
            check(hasWindowsLibpng) {
                "未找到 Windows libpng 依赖，请设置 VCPKG_ROOT 或 -Pssoptimizer.native.windows.libpng.root。"
            }
            check(hasWindowsFreetype) {
                "未找到 Windows freetype 依赖，请设置 VCPKG_ROOT 或 -Pssoptimizer.native.windows.freetype.root。"
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
            commonArgs.addAll(windowsLibpngCompilerArgs)
            commonArgs.addAll(windowsFreetypeCompilerArgs)
            if (hasWindowsLibpng) {
                commonArgs.add("-DSSOPTIMIZER_HAVE_LIBPNG=1")
            }
            if (hasWindowsFreetype) {
                commonArgs.add("-DSSOPTIMIZER_HAVE_FREETYPE=1")
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
            argsList.addAll(listOf("-lopengl32", "-luser32", "-limm32"))
            argsList.addAll(windowsLibpngLinkerArgs)
            argsList.addAll(windowsFreetypeLinkerArgs)
            argsList.addAll(windowsZlibLinkerArgs)
            argsList.addAll(windowsBzip2LinkerArgs)
            argsList.addAll(windowsBrotliLinkerArgs)

            runCommand(workingDir, environment, listOf(windowsCrossCxx) + argsList)
        }
    }

    tasks.named("assemble") {
        dependsOn("linkWindowsCrossSharedLibrary")
    }

    tasks.named("build") {
        dependsOn("linkWindowsCrossSharedLibrary")
    }
}
