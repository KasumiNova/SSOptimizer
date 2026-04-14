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

val starsectorGameDir = providers.gradleProperty("starsector.gameDir").orNull
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

dependencies {
    api("org.ow2.asm:asm:9.9.1")
    api("org.ow2.asm:asm-commons:9.9.1")

    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.0")
    testImplementation("org.ow2.asm:asm-tree:9.9.1")
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

    onlyIf { starsectorGameDir != null }

    doFirst {
        val gameDir = file(starsectorGameDir!!)
        val jarDir = resolveGameJarDirectory(starsectorGameDir!!)
        val inputJars = fileTree(jarDir) {
            include("*.jar")
            exclude("*-sources.jar", "*-javadoc.jar")
        }
            .files
            .sortedBy { it.name }
        require(inputJars.isNotEmpty()) {
            "未在 Starsector 目录中找到可 remap 的 JAR: $gameDir (resolvedJarDir=$jarDir)"
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