plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

val starsectorGameDir = providers.gradleProperty("starsector.gameDir").orNull?.takeIf { it.isNotBlank() }
val reobfJarFile = rootProject.layout.buildDirectory.file("libs/SSOptimizer-reobf.jar")
val appJarFile = project(":app").layout.buildDirectory.file("libs/SSOptimizer.jar")

fun detectMappingPlatform(gameDirPath: String?): String {
    val gameDir = gameDirPath?.let(::file)
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

    val osName = System.getProperty("os.name", "").lowercase()
    return if (osName.contains("win")) "windows" else "linux"
}

val mappingPlatform = providers.gradleProperty("starsector.platform")
    .orElse(providers.provider { detectMappingPlatform(starsectorGameDir) })
val namedGameJarsDir = mappingPlatform.map { platform ->
    rootProject.layout.buildDirectory.dir("named-game-jars/$platform").get().asFile
}

fun resolveGameJarDirectory(gameDirPath: String): File {
    val gameDir = file(gameDirPath)
    val starsectorCoreDir = gameDir.resolve("starsector-core")
    return if (starsectorCoreDir.isDirectory) starsectorCoreDir else gameDir
}

/**
 * CI 模式下使用的游戏 classpath 第三方依赖——与 Starsector 0.98a-RC8 运行时版本对齐。
 * 当 starsector.gameDir 未设置时，这些 jar 和 game-jars/{platform}/ 中的 vendor jar
 * 一起传入 remapper，替代从游戏安装目录读取 jar 的行为。
 */
val gameClasspath: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    api("org.ow2.asm:asm:9.9.1")
    api("org.ow2.asm:asm-commons:9.9.1")

    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.0")
    testImplementation("org.ow2.asm:asm-tree:9.9.1")

    // Starsector 0.98a-RC8 运行时 classpath 上的第三方库（用于 remap 时的类层次解析）
    gameClasspath("org.lwjgl.lwjgl:lwjgl:2.9.3")
    gameClasspath("org.lwjgl.lwjgl:lwjgl_util:2.9.3")
    gameClasspath("com.thoughtworks.xstream:xstream:1.4.10")
    gameClasspath("org.codehaus.janino:janino:2.7.8")
    gameClasspath("org.codehaus.janino:commons-compiler:2.7.8")
    gameClasspath("org.codehaus.janino:commons-compiler-jdk:2.7.8")
    gameClasspath("log4j:log4j:1.2.9")
    gameClasspath("org.json:json:20231013")
    gameClasspath("javax.xml.bind:jaxb-api:2.4.0-b180830.0359")
    gameClasspath("org.glassfish.jaxb:txw2:3.0.2")
    gameClasspath("org.sejda.imageio:webp-imageio:0.1.6")
    gameClasspath("net.java.jinput:jinput:2.0.7")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("remapGameClasspathToNamed") {
    group = "mapping"
    description = "Remap Starsector compile classpath jars to named namespace"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("github.kasuminova.ssoptimizer.mapping.JarRemapCli")
    systemProperty("ssoptimizer.mapping.platform", mappingPlatform.get())

    outputs.dir(namedGameJarsDir)

    doFirst {
        val inputJars: List<File>
        if (starsectorGameDir != null) {
            // 本地开发模式：从游戏安装目录读取全部 jar
            val jarDir = resolveGameJarDirectory(starsectorGameDir!!)
            inputJars = fileTree(jarDir) {
                include("*.jar")
                exclude("*-sources.jar", "*-javadoc.jar")
            }
                .files
                .sortedBy { it.name }
            require(inputJars.isNotEmpty()) {
                "未在 Starsector 目录中找到可 remap 的 JAR: ${file(starsectorGameDir!!)} (resolvedJarDir=$jarDir)"
            }
        } else {
            // CI 模式：从 game-jars/{platform}/ 读取 vendor jar + Maven 解析第三方 jar
            val platform = mappingPlatform.get()
            val vendorDir = rootProject.file("game-jars/$platform")
            require(vendorDir.isDirectory) {
                "CI 模式下未找到 vendor jar 目录: $vendorDir — 请确认 game-jars/$platform/ 下有平台专属 jar"
            }
            val vendorJars = fileTree(vendorDir) { include("*.jar") }
                .files
                .sortedBy { it.name }
            require(vendorJars.isNotEmpty()) {
                "vendor jar 目录为空: $vendorDir"
            }
            val thirdPartyJars = configurations["gameClasspath"]
                .resolve()
                .sortedBy { it.name }
            inputJars = (vendorJars + thirdPartyJars).sortedBy { it.name }
            logger.lifecycle("[remapGameClasspathToNamed] CI 模式: ${vendorJars.size} vendor jar + ${thirdPartyJars.size} 第三方 jar")
        }

        val outputDir = namedGameJarsDir.get()
        outputDir.parentFile.mkdirs()
        outputDir.mkdirs()

        args(listOf("batch", "obf-to-named", outputDir.absolutePath) + inputJars.map { it.absolutePath })
    }
}

tasks.register<JavaExec>("reobfuscateAppJar") {
    group = "mapping"
    description = "Remap mapped app jar back to obfuscated namespace"
    dependsOn(tasks.named("classes"), ":app:jar")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("github.kasuminova.ssoptimizer.mapping.JarRemapCli")
    systemProperty("ssoptimizer.mapping.platform", mappingPlatform.get())

    inputs.file(appJarFile)
    outputs.file(reobfJarFile)

    doFirst {
        val outputFile = reobfJarFile.get().asFile
        outputFile.parentFile.mkdirs()
        if (outputFile.exists()) {
            outputFile.delete()
        }

        args(
            "single",
            "named-to-obf",
            appJarFile.get().asFile.absolutePath,
            outputFile.absolutePath
        )
    }
}