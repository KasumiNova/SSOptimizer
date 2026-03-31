import org.gradle.language.cpp.tasks.CppCompile
import org.gradle.nativeplatform.tasks.LinkSharedLibrary
import org.gradle.nativeplatform.Linkage

import java.io.ByteArrayOutputStream

plugins {
    `cpp-library`
}

val javaHome = file(System.getProperty("java.home"))
val jdkHome = if (javaHome.resolve("include").exists()) {
    javaHome
} else {
    javaHome.parentFile ?: javaHome
}
val jniIncludeDir = jdkHome.resolve("include")
val jniIncludeLinuxDir = jniIncludeDir.resolve("linux")

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

library {
    // 当前环境是 Linux，先锁定 x86_64，后续可扩展 machines.windows/macos
    targetMachines.set(listOf(machines.linux.x86_64))
    linkage.set(listOf(Linkage.SHARED))

    // 预留 generated 目录给 Java -h 产出的 JNI 头文件
    privateHeaders.from(file("src/main/headers"), file("src/main/headers/generated"))
}

tasks.withType<CppCompile>().configureEach {
    dependsOn(":app:compileJava")
    compilerArgs.addAll(
        listOf(
            "-std=c++20",
            "-O3",
            "-fno-math-errno",
            "-fno-trapping-math",
            "-fPIC",
            "-I${jniIncludeDir.absolutePath}",
            "-I${jniIncludeLinuxDir.absolutePath}"
        )
    )
    compilerArgs.addAll(libpngCompilerArgs)
    compilerArgs.addAll(freetypeCompilerArgs)
    if (hasLibpng) {
        compilerArgs.add("-DSSOPTIMIZER_HAVE_LIBPNG=1")
    }
    if (hasFreetype) {
        compilerArgs.add("-DSSOPTIMIZER_HAVE_FREETYPE=1")
    }
}

tasks.withType<LinkSharedLibrary>().configureEach {
    linkerArgs.addAll(listOf("-lGL"))
    linkerArgs.addAll(libpngLinkerArgs)
    linkerArgs.addAll(freetypeLinkerArgs)
}
