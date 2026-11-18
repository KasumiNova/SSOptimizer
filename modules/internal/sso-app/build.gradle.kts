plugins {
    application
    id("io.github.nanoforged.sectordevgradle.nanoforge") version "0.1.0-SNAPSHOT"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

val namedGameJarBaseNames = listOf("starfarer_obf", "starfarer.api", "fs.common_obf", "fs.sound_obf")

val shade = configurations.create("shade") {
    isCanBeConsumed = false
}

// gameLibraries 由 SDG 在 afterEvaluate 中创建，扩展 testRuntimeClasspath 必须延后。
project.afterEvaluate {
    configurations.testRuntimeClasspath {
        extendsFrom(configurations.named("gameLibraries").get())
    }
}

repositories {
    maven {
        url = uri("https://nexus.gtnewhorizons.com/repository/releases/")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.named<JavaCompile>("compileJava") {
    options.headerOutputDirectory.set(layout.projectDirectory.dir("../../../native-headers/generated"))
}

application {
    mainClass.set("github.kasuminova.ssoptimizer.App")
}

starsector {
    modId.set("ssoptimizer")
    modName.set("SSOptimizer")
    author.set("Hikari_Nova")
    description.set("Starsector rendering & performance optimizer")
    gameVersion.set("0.98a-RC8")
    modPlugin.set("github.kasuminova.ssoptimizer.SSOptimizerModPlugin")
    gameDir.set(layout.dir(providers.gradleProperty("starsector.gameDir")
        .orElse(providers.environmentVariable("SSOPTIMIZER_GAME_DIR"))
        .filter { it.isNotBlank() }
        .map { file(it) }))
    manageEnabledMods.set(true)
    // 部署必须保留 cache，避免字体与纹理缓存被清空。
    deployPreserve.set(listOf("cache"))
    providers.gradleProperty("sourcesector.namedRepo").orNull?.let { sourceRepo.set(file(it)) }
}

nanoforge {
    coremod.set(true)
    pluginClass.set("github.kasuminova.ssoptimizer.bootstrap.SSOptimizerCorePlugin")
    authors.set(listOf("kasuminova"))
    // 两个 transformer 的改写目标互不重叠，声明顺序不构成依赖。
    asmTransformers.set(listOf(
        "github.kasuminova.ssoptimizer.bootstrap.HybridWeaverTransformer",
        "github.kasuminova.ssoptimizer.bootstrap.RenderThreadRedirectTransformer"
    ))
    mixinConfigs.set(listOf("mixins.ssoptimizer.json"))
}

dependencies {
    implementation(project(":modules:api:sso-api"))
    implementation(project(":modules:internal:sso-core"))
    implementation(project(":modules:internal:sso-ai"))
    implementation(project(":modules:internal:sso-ime"))
    implementation(project(":modules:internal:sso-save"))
    implementation(project(":modules:internal:sso-modopt"))
    implementation(project(":modules:internal:sso-automation"))
    implementation(project(":modules:internal:sso-debug"))
    implementation(project(":modules:internal:sso-loading"))
    implementation(project(":modules:internal:sso-render"))
    implementation(project(":modules:internal:sso-font"))

    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.0")
    // RFB/LaunchWrapper 传递的旧版 Gson 缺少 parseString，测试需固定此版本。
    testImplementation("com.google.code.gson:gson:2.13.1")
    testImplementation("log4j:log4j:1.2.17")
    testImplementation("org.apache.logging.log4j:log4j-core:2.25.2")

    implementation("it.unimi.dsi:fastutil:8.5.18")
    implementation("org.jctools:jctools-core:4.0.5")
    implementation("com.github.luben:zstd-jni:1.5.7-3")
    implementation("tools.profiler:async-profiler:4.5")
    shade("org.apache.logging.log4j:log4j-1.2-api:2.25.2") {
        isTransitive = false
    }

    compileOnly("io.github.nanoforged:NanoForge:0.1.0-SNAPSHOT") {
        isTransitive = false
    }
    compileOnly("com.gtnewhorizons.retrofuturabootstrap:RetroFuturaBootstrap:1.0.12") {
        isTransitive = false
    }
    compileOnly("org.ow2.asm:asm:9.8")
    compileOnly("org.ow2.asm:asm-commons:9.8")
    compileOnly("org.ow2.asm:asm-tree:9.8")
    compileOnly("net.fabricmc:sponge-mixin:0.16.3+mixin.0.8.7")
    compileOnly("org.apache.logging.log4j:log4j-1.2-api:2.25.2")
    compileOnly("org.apache.logging.log4j:log4j-api:2.25.2")

    testImplementation("io.github.nanoforged:NanoForge:0.1.0-SNAPSHOT") {
        isTransitive = false
    }
    testImplementation("com.gtnewhorizons.retrofuturabootstrap:RetroFuturaBootstrap:1.0.12") {
        isTransitive = false
    }
    testImplementation("org.ow2.asm:asm:9.8")
    testImplementation("org.ow2.asm:asm-commons:9.8")
    testImplementation("org.ow2.asm:asm-tree:9.8")
    testImplementation("org.ow2.asm:asm-util:9.8")
    testImplementation("net.fabricmc:sponge-mixin:0.16.3+mixin.0.8.7")
    // 无参 XStream 使用 XppDriver，测试运行期需补充游戏 jar 未携带的 XML Pull API 与 MXParser。
    testRuntimeOnly("xmlpull:xmlpull:1.1.3.1")
    testRuntimeOnly("xpp3:xpp3_min:1.1.4c")

    namedGameJarBaseNames.forEach { baseName ->
        testImplementation("starsector.named:$baseName:0.98a-RC8-SNAPSHOT")
    }
    testImplementation("starsector.game:lwjgl:0.98a-RC8-SNAPSHOT")
}

tasks.test {
    useJUnitPlatform()
    // JDK 仅在启动期读取自 attach 开关，运行期设置该属性无效。
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
    // nativeIT 的 native 库与模块加载缓存会驻留测试 JVM，必须与普通测试隔离。
    exclude("**/*IT.class")
    dependsOn(tasks.named("jar"))
    dependsOn("modProduction")
    dependsOn(":stageWindowsOverlay")
    systemProperty("project.rootDir", rootProject.rootDir.absolutePath)
    doFirst {
        systemProperty("ssoptimizer.coremod.jar", tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath)
        systemProperty("ssoptimizer.windowsOverlay.dir",
            rootProject.layout.buildDirectory.dir("user-package/windows-overlay").get().asFile.absolutePath)
    }
    // JDK 25 的 XStream 集成测试需要与游戏运行时相同的模块开放。
    jvmArgs(
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.ref=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED"
    )
}

tasks.register<Test>("nativeIT") {
    group = "verification"
    description = "Run native-library integration tests (*IT) in a dedicated JVM"
    dependsOn(
        ":modules:internal:sso-render:native-render:assembleRelease",
        ":modules:internal:sso-loading:native-loading:assembleRelease",
        ":modules:internal:sso-loading:native-texcompress:assembleRelease",
        ":modules:internal:sso-font:native-font:assembleRelease",
        ":modules:internal:sso-ime:native-ime:assembleRelease"
    )
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("**/*IT.class")
    systemProperty("project.rootDir", rootProject.rootDir.absolutePath)
    jvmArgs(
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.ref=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED"
    )
}

tasks.named("check") {
    dependsOn("nativeIT")
}

val docsTestSourceSet = sourceSets.create("docsTest") {
    java.srcDir("src/docsTest/java")
}

val jmhSourceSet = sourceSets.create("jmh") {
    java.srcDir("src/jmh/java")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

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
}

tasks.register<JavaExec>("jmh") {
    group = "benchmark"
    description = "Run JMH benchmarks"
    dependsOn(":modules:internal:sso-render:native-render:assemble")
    dependsOn(":modules:internal:sso-loading:native-loading:assemble")
    dependsOn(tasks.named(jmhSourceSet.classesTaskName))
    classpath = jmhSourceSet.runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    val includePattern = providers.gradleProperty("jmhInclude").orElse(".*CollisionGridCollectorBenchmark.*")
    val warmupIterations = providers.gradleProperty("jmhWarmupIterations").orElse("3")
    val measurementIterations = providers.gradleProperty("jmhIterations").orElse("5")
    val warmupTime = providers.gradleProperty("jmhWarmupTime").orElse("300ms")
    val measurementTime = providers.gradleProperty("jmhTime").orElse("300ms")
    val forks = providers.gradleProperty("jmhForks").orElse("1")
    val timeUnit = providers.gradleProperty("jmhTimeUnit").orElse("us")
    val jmhCorpus = providers.gradleProperty("jmhCorpus").orNull
    val jmhQueueCapacity = providers.gradleProperty("jmhQueueCapacity").orNull
    val jmhBatchSize = providers.gradleProperty("jmhBatchSize").orNull
    val jmhSaveCorpusDir = providers.gradleProperty("jmhSaveCorpusDir").orNull
    val nativeLibraryPath = providers.gradleProperty("jmhNativePath").orElse(
        layout.projectDirectory.file("../sso-render/native/build/lib/main/debug/${System.mapLibraryName("ssoptimizer_render")}").asFile.absolutePath
    )
    val nativeLoadingLibraryPath = providers.gradleProperty("jmhNativeLoadingPath").orElse(
        layout.projectDirectory.file("../sso-loading/native/build/lib/main/debug/${System.mapLibraryName("ssoptimizer_loading")}").asFile.absolutePath
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
        "-jvmArgsAppend", "-Dssoptimizer.native.path=${nativeLibraryPath.get()} -Dssoptimizer.native.path.loading=${nativeLoadingLibraryPath.get()}",
        *if (jmhSaveCorpusDir != null) arrayOf("-jvmArgsAppend", "-Dssoptimizer.saveCorpusDir=$jmhSaveCorpusDir") else emptyArray(),
        *extraArgs.toTypedArray()
    )
}

tasks.register<Jar>("standalonePayload") {
    archiveFileName.set("SSOptimizer-payload.jar")
    from(sourceSets.main.get().output)
    from({ configurations.runtimeClasspath.get().filter { it.extension == "jar" }.map { zipTree(it) } })
    dependsOn(configurations.runtimeClasspath)
    exclude("github/kasuminova/ssoptimizer/bootstrap/SSOptimizerCorePlugin*.class",
        "coremod.toml", "nanoforge.mod.toml", "module-info.class", "META-INF/versions/**",
        "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
    manifest.attributes("SSOptimizer-Namespace" to "named")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("SSOptimizer")
    archiveVersion.set("")
    archiveClassifier.set("")

    dependsOn(configurations.runtimeClasspath, shade)
    from({
        (configurations.runtimeClasspath.get().files + shade.resolve())
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    // RFB 会将含 module-info 的 jar 按命名模块加载，且不解析 multi-release 变体，必须剔除这两类条目。
    exclude("module-info.class", "META-INF/versions/**")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
