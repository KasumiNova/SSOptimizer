plugins {
    application
}

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

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

val starsectorGameDir = providers.gradleProperty("starsector.gameDir").orNull?.takeIf { it.isNotBlank() }
val mappingPlatform = providers.gradleProperty("starsector.platform")
    .orElse(providers.provider { detectMappingPlatform(starsectorGameDir) })

/** 游戏本体 jar 基名——与 mapping 模块发布的本地仓库 artifactId 一一对应。 */
val namedGameJarBaseNames = listOf("starfarer_obf", "starfarer.api", "fs.common_obf", "fs.sound_obf")

repositories {
    // named 游戏 jar 本地仓库（mapping:publishNamedGameJars 发布，附带 -sources.jar，IDEA 同步时自动附加源码）
    maven {
        url = uri(rootProject.layout.buildDirectory.dir("named-game-repo/${mappingPlatform.get()}"))
    }
}

configurations.all {
    // named 游戏 jar 以 SNAPSHOT 发布：mapping 变更重发布后每次解析都取新产物
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.named<JavaCompile>("compileJava") {
    // 为后续 JNI 架构预留：自动导出头文件到 native 模块
    options.headerOutputDirectory.set(layout.projectDirectory.dir("../native/src/main/headers/generated"))
}

application {
    mainClass.set("github.kasuminova.ssoptimizer.App")
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(gradleTestKit())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.0")
    testImplementation("log4j:log4j:1.2.17")

    implementation(project(":mapping"))
    implementation(project(":agent-api"))
    runtimeOnly(project(":mod-optimizations"))
    implementation("org.ow2.asm:asm:9.9.1")
    implementation("org.ow2.asm:asm-commons:9.9.1")
    implementation("org.ow2.asm:asm-tree:9.9.1")
    implementation("it.unimi.dsi:fastutil:8.5.18")
    implementation("org.jctools:jctools-core:4.0.5")
    implementation("net.fabricmc:sponge-mixin:0.15.4+mixin.0.8.7")
    implementation("com.github.luben:zstd-jni:1.5.7-3")
    compileOnly("log4j:log4j:1.2.17")

    // 命名空间游戏 jar（由 mapping:remapGameClasspathToNamed 产出）
    // 本地开发模式从 gameDir 读取；CI 模式从 game-jars/{platform}/ + Maven 依赖解析
    val namedGameClasspath = rootProject.files(rootProject.provider {
        val dir = rootProject.layout.buildDirectory.dir("named-game-jars/${mappingPlatform.get()}").get().asFile
        dir.listFiles()
            // 游戏本体 jar 走 starsector.named 模块依赖（含 sources），这里只保留透传的第三方 jar
            ?.filter { it.isFile && it.extension == "jar" && it.nameWithoutExtension !in namedGameJarBaseNames }
            ?: emptyList()
    })
    namedGameClasspath.builtBy(":mapping:remapGameClasspathToNamed")

    // named 游戏本体 jar（模块依赖，来自 mapping 发布的本地仓库；附带 -sources.jar 供 IDE 索引）
    namedGameJarBaseNames.forEach { baseName ->
        compileOnly("starsector.named:$baseName:0.98a-RC8-SNAPSHOT")
        testImplementation("starsector.named:$baseName:0.98a-RC8-SNAPSHOT")
    }

    // 混淆原始游戏 jar（入库 vendor jar）：named jar 全量改名后，运行期模拟测试
    // （Mixin 桥接/ASM 转换，工作在混淆命名空间）仍需从 obf 视图读取类字节码与解析类层次
    val obfGameClasspath = rootProject.files(rootProject.provider {
        val dir = rootProject.file("game-jars/${mappingPlatform.get()}")
        dir.listFiles()
            ?.filter { it.isFile && it.extension == "jar" }
            ?: emptyList()
    })

    compileOnly(namedGameClasspath)
    testImplementation(namedGameClasspath)
    testImplementation(obfGameClasspath)
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(":mapping:publishNamedGameJars")
}
tasks.named<JavaCompile>("compileTestJava") {
    dependsOn(":mapping:publishNamedGameJars")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.named("jar"))
    systemProperty("project.rootDir", rootProject.rootDir.absolutePath)
    val starsectorGameDir = providers.gradleProperty("starsector.gameDir").orNull?.takeIf { it.isNotBlank() }
    val mappingPlatform = providers.gradleProperty("starsector.platform")
        .orElse(providers.provider { detectMappingPlatform(starsectorGameDir) })
    systemProperty("ssoptimizer.mapping.platform", mappingPlatform.get())
    doFirst {
        // named/obf 双视图共存于测试 classpath：同名类必须从 named jar 加载（obf 仅以字节按路径读取）。
        // 游戏 jar 改模块依赖后解析顺序不再保证 named 在前，这里显式把 obf jar 移到末尾。
        classpath = files(classpath.files.sortedBy {
            if (it.absolutePath.replace('\\', '/').contains("/game-jars/")) 1 else 0
        })
    }
}

val docsTestSourceSet = sourceSets.create("docsTest") {
    java.srcDir("src/docsTest/java")
}

val jmhSourceSet = sourceSets.create("jmh") {
    java.srcDir("src/jmh/java")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

configurations[docsTestSourceSet.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get()
)
configurations[docsTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get()
)
configurations[jmhSourceSet.implementationConfigurationName].extendsFrom(
    configurations.implementation.get()
)
configurations[jmhSourceSet.runtimeOnlyConfigurationName].extendsFrom(
    configurations.runtimeOnly.get()
)

dependencies {
    "docsTestImplementation"(platform("org.junit:junit-bom:5.13.0"))
    "docsTestImplementation"("org.junit.jupiter:junit-jupiter")
    "docsTestRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.13.0")

    "jmhImplementation"("org.openjdk.jmh:jmh-core:1.37")
    "jmhImplementation"("org.glassfish.jaxb:txw2:3.0.2")
    "jmhImplementation"(project(":mod-optimizations"))
    "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    "jmhRuntimeOnly"("log4j:log4j:1.2.17")
}

tasks.register<Test>("docsTest") {
    group = "verification"
    description = "Run documentation contract tests"
    testClassesDirs = docsTestSourceSet.output.classesDirs
    classpath = docsTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    systemProperty("project.rootDir", rootProject.rootDir.absolutePath)
    val starsectorGameDir = providers.gradleProperty("starsector.gameDir").orNull?.takeIf { it.isNotBlank() }
    val mappingPlatform = providers.gradleProperty("starsector.platform")
        .orElse(providers.provider { detectMappingPlatform(starsectorGameDir) })
    systemProperty("ssoptimizer.mapping.platform", mappingPlatform.get())
    doFirst {
        // 与 test 同理：obf 游戏 jar 移到 classpath 末尾，保证同名类从 named jar 加载。
        classpath = files(classpath.files.sortedBy {
            if (it.absolutePath.replace('\\', '/').contains("/game-jars/")) 1 else 0
        })
    }
}

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Run JMH benchmarks"
    dependsOn(":native:assemble")
    dependsOn(tasks.named(jmhSourceSet.classesTaskName))
    classpath = jmhSourceSet.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    val includePattern = providers.gradleProperty("jmhInclude").orElse(".*CollisionGridCollectorBenchmark.*")
    val warmupIterations = providers.gradleProperty("jmhWarmupIterations").orElse("3")
    val measurementIterations = providers.gradleProperty("jmhIterations").orElse("5")
    val warmupTime = providers.gradleProperty("jmhWarmupTime").orElse("300ms")
    val measurementTime = providers.gradleProperty("jmhMeasurementTime").orElse("300ms")
    val forks = providers.gradleProperty("jmhForks").orElse("1")
    val timeUnit = providers.gradleProperty("jmhTimeUnit").orElse("us")
    val jmhCorpus = providers.gradleProperty("jmhCorpus").orNull
    val jmhQueueCapacity = providers.gradleProperty("jmhQueueCapacity").orNull
    val jmhBatchSize = providers.gradleProperty("jmhBatchSize").orNull
    val jmhSaveCorpusDir = providers.gradleProperty("jmhSaveCorpusDir").orNull
    val nativeLibraryPath = providers.gradleProperty("jmhNativePath").orElse(
        layout.projectDirectory.file("../native/build/lib/main/debug/${System.mapLibraryName("native")}").asFile.absolutePath
    )
    val extraArgs = mutableListOf<String>()
    if (jmhCorpus != null) {
        extraArgs += listOf("-p", "corpus=$jmhCorpus")
    }
    if (jmhQueueCapacity != null) {
        extraArgs += listOf("-p", "queueCapacity=$jmhQueueCapacity")
    }
    if (jmhBatchSize != null) {
        extraArgs += listOf("-p", "batchSize=$jmhBatchSize")
    }
    args(
        includePattern.get(),
        "-wi", warmupIterations.get(),
        "-i", measurementIterations.get(),
        "-f", forks.get(),
        "-w", warmupTime.get(),
        "-r", measurementTime.get(),
        "-bm", "avgt",
        "-tu", timeUnit.get(),
        "-jvmArgsAppend", "-Dssoptimizer.native.path=${nativeLibraryPath.get()}",
        *if (jmhSaveCorpusDir != null) arrayOf("-jvmArgsAppend", "-Dssoptimizer.saveCorpusDir=$jmhSaveCorpusDir") else emptyArray(),
        *extraArgs.toTypedArray()
    )
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("SSOptimizer")
    archiveVersion.set("")
    archiveClassifier.set("")

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Premain-Class" to "github.kasuminova.ssoptimizer.bootstrap.SSOptimizerAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true"
        )
    }
}
