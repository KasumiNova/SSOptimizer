plugins {
    `java-library`
    `maven-publish`
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
/** named 游戏 jar 本地 Maven 仓库目录（app 以模块依赖消费，IDEA 同步时自动附加 sources）。 */
val namedGameRepoDir = mappingPlatform.map { platform ->
    rootProject.layout.buildDirectory.dir("named-game-repo/$platform").get().asFile
}
/**
 * 游戏本体 jar 基名（vendored obf jar，remap 后进入 named 命名空间）。
 * 第三方 jar remap 时原样透传，不进入本地仓库，app 仍按文件依赖消费。
 */
val gameJarBaseNames = listOf("starfarer_obf", "starfarer.api", "fs.common_obf", "fs.sound_obf")

/** 发布物名称（Gradle 任务名片段）：starfarer_obf → namedStarfarerObf。 */
fun namedGamePublicationName(baseName: String): String =
    "named" + baseName.split('.', '_').joinToString("") { part ->
        part.replaceFirstChar { it.uppercase() }
    }

/** 全量映射生成物目录（mapping/build/generated/mappings/{platform}/，生成物不入库）。 */
val generatedMappingsDir = layout.buildDirectory.dir("generated/mappings")
/** 映射报告目录（mapping/build/reports/，报告不入库）。 */
val mappingReportsDir = layout.buildDirectory.dir("reports")
/** 当前平台的全量映射文件（由 generateFullMappings 产出）。 */
val fullMappingFile = mappingPlatform.map { platform ->
    generatedMappingsDir.get().file("$platform/ssoptimizer-$platform-full.tiny").asFile
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

/**
 * Vineflower 反编译器 classpath——把 named 游戏 jar 反编译为 -sources.jar，
 * 随本地仓库一同发布，IDEA 同步时自动附加为源码（替代内置反编译视图）。
 */
val vineflower: Configuration by configurations.creating {
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

    vineflower("org.vineflower:vineflower:1.12.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("generateFullMappings") {
    group = "mapping"
    description = "Generate full (placeholder + human) mappings for both platforms from vendored game jars"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("github.kasuminova.ssoptimizer.mapping.gen.FullMappingCli")

    val gameJarsRoot = rootProject.file("game-jars")
    val humanMappingsDir = file("src/main/resources/mappings")

    inputs.dir(gameJarsRoot)
    inputs.dir(humanMappingsDir)
    outputs.dir(generatedMappingsDir)
    outputs.dir(mappingReportsDir)

    doFirst {
        args(listOf(
            gameJarsRoot.absolutePath,
            humanMappingsDir.absolutePath,
            generatedMappingsDir.get().asFile.absolutePath,
            mappingReportsDir.get().asFile.absolutePath
        ))
    }
}

tasks.register<JavaExec>("mergeScopeFragments") {
    group = "mapping"
    description = "Validate scope mapping fragments (parse, jar consistency, cross-scope uniqueness) and write coverage report"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("github.kasuminova.ssoptimizer.mapping.gen.ScopeFragmentCli")

    val gameJarsRoot = rootProject.file("game-jars")
    val humanMappingsDir = file("src/main/resources/mappings")
    val reportFile = mappingReportsDir.map { it.file("scope-fragments.txt") }

    inputs.dir(gameJarsRoot)
    inputs.dir(humanMappingsDir)
    outputs.file(reportFile)

    doFirst {
        args(listOf(
            gameJarsRoot.absolutePath,
            humanMappingsDir.absolutePath,
            reportFile.get().asFile.absolutePath
        ))
    }
}

tasks.register<JavaExec>("scanMappingUsage") {
    group = "mapping"
    description = "Scan consumer bytecode (app/agent-api/mod-optimizations) for game class/member references, classify by mapping layer"
    dependsOn(
        tasks.named("classes"), "generateFullMappings",
        ":app:jar", ":app:compileTestJava", ":agent-api:jar", ":mod-optimizations:jar"
    )
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("github.kasuminova.ssoptimizer.mapping.gen.UsageScanCli")

    val humanMappingsDir = file("src/main/resources/mappings")
    val consumerInputs = listOf(
        project(":app").tasks.named<Jar>("jar").flatMap { it.archiveFile },
        project(":agent-api").tasks.named<Jar>("jar").flatMap { it.archiveFile },
        project(":mod-optimizations").tasks.named<Jar>("jar").flatMap { it.archiveFile },
        project(":app").layout.buildDirectory.dir("classes/java/test")
    )
    inputs.dir(humanMappingsDir)
    inputs.dir(generatedMappingsDir)
    inputs.files(consumerInputs)
    outputs.files(listOf("linux", "windows").map { platformId ->
        mappingReportsDir.map { it.file("mapping-usage-$platformId.txt") }
    })

    doFirst {
        args(listOf(
            humanMappingsDir.absolutePath,
            generatedMappingsDir.get().asFile.absolutePath,
            mappingReportsDir.get().asFile.absolutePath
        ) + consumerInputs.map { it.get().asFile.absolutePath })
    }
}

tasks.register<JavaExec>("remapGameClasspathToNamed") {
    group = "mapping"
    description = "Remap Starsector compile classpath jars to named namespace"
    dependsOn(tasks.named("classes"), "generateFullMappings")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("github.kasuminova.ssoptimizer.mapping.JarRemapCli")
    systemProperty("ssoptimizer.mapping.platform", mappingPlatform.get())

    // 消费构建期全量表（人工条目优先 + 占位名），tiny 源或生成器输入变更会触发重跑。
    inputs.files(fullMappingFile)
    if (starsectorGameDir != null) {
        inputs.dir(resolveGameJarDirectory(starsectorGameDir!!))
    } else {
        inputs.files(mappingPlatform.map { platform ->
            fileTree(rootProject.file("game-jars/$platform")) { include("*.jar") }
        })
        inputs.files(configurations["gameClasspath"])
    }
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

        args(listOf(
            "--mapping=${fullMappingFile.get().absolutePath}",
            "batch", "obf-to-named", outputDir.absolutePath
        ) + inputJars.map { it.absolutePath })
    }
}

gameJarBaseNames.forEach { baseName ->
    val publicationName = namedGamePublicationName(baseName)
    tasks.register<JavaExec>("decompile${publicationName}ToSources") {
        group = "mapping"
        description = "Decompile named $baseName.jar with Vineflower into $baseName-sources.jar for IDE source attachment"
        dependsOn("remapGameClasspathToNamed")

        classpath = vineflower
        mainClass.set("org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler")

        val inputJar = mappingPlatform.map { platform ->
            rootProject.layout.buildDirectory.file("named-game-jars/$platform/$baseName.jar").get().asFile
        }
        val outputJar = mappingPlatform.map { platform ->
            rootProject.layout.buildDirectory.file("named-game-jars/$platform-sources/$baseName-sources.jar").get().asFile
        }
        inputs.file(inputJar)
        outputs.file(outputJar)

        doFirst {
            require(inputJar.get().isFile) {
                "named 游戏 jar 不存在: ${inputJar.get()} — 请先运行 :mapping:remapGameClasspathToNamed"
            }
            outputJar.get().parentFile.mkdirs()
            args(listOf("-dgs=1", inputJar.get().absolutePath, outputJar.get().absolutePath))
        }
    }
}

tasks.register("decompileNamedGameJars") {
    group = "mapping"
    description = "Decompile all named game jars with Vineflower into -sources.jar for IDE source attachment"
    gameJarBaseNames.forEach { baseName ->
        dependsOn("decompile${namedGamePublicationName(baseName)}ToSources")
    }
}

publishing {
    publications {
        gameJarBaseNames.forEach { baseName ->
            create<MavenPublication>(namedGamePublicationName(baseName)) {
                groupId = "starsector.named"
                artifactId = baseName
                // SNAPSHOT + app 端 cacheChangingModulesFor(0)：mapping 变更重发布后 IDE 同步即取新 jar
                version = "0.98a-RC8-SNAPSHOT"
                val platformId = mappingPlatform.get()
                artifact(rootProject.layout.buildDirectory
                    .file("named-game-jars/$platformId/$baseName.jar").get().asFile) {
                    builtBy(tasks.named("remapGameClasspathToNamed"))
                }
                artifact(rootProject.layout.buildDirectory
                    .file("named-game-jars/$platformId-sources/$baseName-sources.jar").get().asFile) {
                    classifier = "sources"
                    builtBy(tasks.named("decompileNamedGameJars"))
                }
            }
        }
    }
    repositories {
        maven {
            name = "namedGameRepo"
            url = namedGameRepoDir.get().toURI()
        }
    }
}

tasks.register("publishNamedGameJars") {
    group = "mapping"
    description = "Publish named game jars + decompiled sources to local repo build/named-game-repo/{platform}"
    gameJarBaseNames.forEach { baseName ->
        val publicationTaskName = namedGamePublicationName(baseName)
            .replaceFirstChar { it.uppercase() }
        dependsOn("publish${publicationTaskName}PublicationToNamedGameRepoRepository")
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