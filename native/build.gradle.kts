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

val javaHome = file(System.getProperty("java.home"))
val jdkHome = if (javaHome.resolve("include").exists()) {
    javaHome
} else {
    javaHome.parentFile ?: javaHome
}
val jniIncludeDir = jdkHome.resolve("include")
val jniPlatformIncludeDir = jniIncludeDir.resolve(if (isWindowsHost) "win32" else "linux")

fun envOrProperty(propertyName: String, envName: String): String? {
    return providers.gradleProperty(propertyName).orNull
        ?: providers.environmentVariable(envName).orNull
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
    val triplet = envOrProperty("ssoptimizer.native.windows.triplet", "VCPKG_DEFAULT_TRIPLET") ?: "x64-windows"
    if (vcpkgRoot.isNullOrBlank()) {
        return null
    }

    val installedRoot = file(vcpkgRoot).resolve("installed").resolve(triplet)
    return installedRoot.takeIf { it.isDirectory }
}

fun includeDirArgsWindows(root: File?): List<String> {
    if (root == null) {
        return emptyList()
    }
    val includeDir = root.resolve("include")
    return if (includeDir.isDirectory) listOf("/I${includeDir.absolutePath}") else emptyList()
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

val windowsLibpngRoot = if (isWindowsHost) resolveWindowsPackageRoot("libpng") else null
val windowsFreetypeRoot = if (isWindowsHost) resolveWindowsPackageRoot("freetype") else null
val windowsLibpngCompilerArgs = if (isWindowsHost) includeDirArgsWindows(windowsLibpngRoot) else emptyList()
val windowsFreetypeCompilerArgs = if (isWindowsHost) includeDirArgsWindows(windowsFreetypeRoot) else emptyList()
val windowsLibpngLinkerArgs = if (isWindowsHost) {
    listOfNotNull(resolveWindowsLibrary(windowsLibpngRoot, "libpng16.lib", "png.lib", "libpng.lib"))
} else {
    emptyList()
}
val windowsFreetypeLinkerArgs = if (isWindowsHost) {
    listOfNotNull(resolveWindowsLibrary(windowsFreetypeRoot, "freetype.lib", "freetyped.lib"))
} else {
    emptyList()
}
val hasWindowsLibpng = windowsLibpngLinkerArgs.isNotEmpty() || !windowsLibpngCompilerArgs.isEmpty()
val hasWindowsFreetype = windowsFreetypeLinkerArgs.isNotEmpty() || !windowsFreetypeCompilerArgs.isEmpty()

library {
    targetMachines.set(listOf(if (isWindowsHost) machines.windows.x86_64 else machines.linux.x86_64))
    linkage.set(listOf(Linkage.SHARED))

    // 预留 generated 目录给 Java -h 产出的 JNI 头文件
    privateHeaders.from(file("src/main/headers"), file("src/main/headers/generated"))
}

tasks.withType<CppCompile>().configureEach {
    dependsOn(":app:compileJava")
    if (isWindowsHost) {
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
    if (isWindowsHost) {
        linkerArgs.addAll(listOf("opengl32.lib", "user32.lib", "imm32.lib"))
        linkerArgs.addAll(windowsLibpngLinkerArgs)
        linkerArgs.addAll(windowsFreetypeLinkerArgs)
    } else {
        linkerArgs.addAll(listOf("-lGL"))
        linkerArgs.addAll(libpngLinkerArgs)
        linkerArgs.addAll(freetypeLinkerArgs)
        linkerArgs.addAll(x11LinkerArgs)
    }
}
