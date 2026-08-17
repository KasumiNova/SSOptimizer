plugins {
    application
    id("io.github.nanoforged.sectordevgradle.nanoforge") version "0.1.0-SNAPSHOT"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

/** 游戏本体 jar 基名——与 SourceSector 发布的本地仓库 artifactId 一一对应。 */
val namedGameJarBaseNames = listOf("starfarer_obf", "starfarer.api", "fs.common_obf", "fs.sound_obf")

/**
 * 需要 shade 进 coremod jar 的额外依赖（log4j 1.2 API 桥接层）。
 * 游戏自带 log4j-1.2.9.jar 已被 NanoForge 启动脚本排除，源码使用的 org.apache.log4j API
 * 由 log4j-1.2-api 桥接到 NanoForge 运行时提供的 log4j2。
 */
val shade = configurations.create("shade") {
    isCanBeConsumed = false
}

// SDG wireGameLibraries（starsector.game:*）在插件 afterEvaluate 阶段才创建 gameLibraries
// 配置，本块的 exclude / extendsFrom 同样推迟到 afterEvaluate（届时配置已就绪）。
// xstream-1.4.10 是游戏安装目录的残留文件——游戏实际运行时 classpath 只用 miko 补丁版
// （xstream-1.4.21_miko，FieldAliasingMapper 等内部 API 与 Maven 原版不同），两者类重复且
// API 不兼容，显式排除 1.4.10，确保编译/测试 classpath 上只有 miko 版生效。
project.afterEvaluate {
    configurations.named("gameLibraries") {
        exclude(group = "starsector.game", module = "xstream-1.4.10")
    }
    // 测试运行期（JUnit）没有游戏 classpath：DCR 集成测试的 XStream 夹具会真实 new XStream()，
    // 将 SDG 供给的游戏第三方 jar 一并纳入 testRuntimeClasspath（xstream-1.4.10 已在上方排除）。
    configurations.testRuntimeClasspath {
        extendsFrom(configurations.named("gameLibraries").get())
    }
}

repositories {
    // RFB / LaunchWrapper（IClassTransformer 编译期依赖）
    // （mavenLocal / mavenCentral / SourceSector named 仓由 SDG 插件统一注册）
    maven {
        url = uri("https://nexus.gtnewhorizons.com/repository/releases/")
    }
}

configurations.all {
    // named 游戏 jar 以 SNAPSHOT 发布：SourceSector 重发布后每次解析都取新产物
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

// ---- SDG：模组元数据唯一事实源（mod_info.json / nanoforge.mod.toml / coremod.toml 均由此派生） ----
starsector {
    modId.set("ssoptimizer")
    modName.set("SSOptimizer")
    author.set("Hikari_Nova")
    description.set("Starsector rendering & performance optimizer")
    gameVersion.set("0.98a-RC8")
    modPlugin.set("github.kasuminova.ssoptimizer.SSOptimizerModPlugin")
    // 游戏目录：-Pstarsector.gameDir 或 SSOPTIMIZER_GAME_DIR；空白视为未设置（CI 不部署）
    gameDir.set(layout.dir(providers.gradleProperty("starsector.gameDir")
        .orElse(providers.environmentVariable("SSOPTIMIZER_GAME_DIR"))
        .filter { it.isNotBlank() }
        .map { file(it) }))
    manageEnabledMods.set(true)
    // SourceSector named 仓路径覆盖（默认取同级 SourceSector 检出；CI 用 -Psourcesector.namedRepo 指定）
    providers.gradleProperty("sourcesector.namedRepo").orNull?.let { sourceRepo.set(file(it)) }
}

nanoforge {
    coremod.set(true)
    pluginClass.set("github.kasuminova.ssoptimizer.bootstrap.SSOptimizerCorePlugin")
    authors.set(listOf("kasuminova"))
    // 顺序敏感：HybridWeaver 的游戏类改写（如 glFinish→hook）必须先于
    // RenderThreadRedirect 的 GL owner 重定向执行，保证 hook 调用点不被二次改写
    asmTransformers.set(listOf(
        "github.kasuminova.ssoptimizer.bootstrap.HybridWeaverTransformer",
        "github.kasuminova.ssoptimizer.bootstrap.RenderThreadRedirectTransformer"
    ))
    mixinConfigs.set(listOf("mixins.ssoptimizer.json"))
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(gradleTestKit())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.0")
    // 测试运行期的 org.apache.log4j 实现（生产运行期为 shade 的 log4j-1.2-api 桥接层）
    testImplementation("log4j:log4j:1.2.17")

    // ---- 打进 coremod jar 的运行时依赖（NanoForge 未提供） ----
    implementation("it.unimi.dsi:fastutil:8.5.18")
    implementation("org.jctools:jctools-core:4.0.5")
    implementation("com.github.luben:zstd-jni:1.5.7-3")
    // async-profiler：API + 内嵌各平台 libasyncProfiler native（基准测试采样接口）
    implementation("tools.profiler:async-profiler:4.5")
    shade("org.apache.logging.log4j:log4j-1.2-api:2.25.2")

    // ---- 运行时由 NanoForge 提供，编译期对齐其版本 ----
    compileOnly("io.github.nanoforged:NanoForge:0.1.0-SNAPSHOT") {
        isTransitive = false
    }
    // RFB 仅需要 LaunchWrapper 的 IClassTransformer，其 pom 传递依赖（log4j 2.0-beta9-fixed
    // 等 legacyfabric 私有构件）与本项目无关，不传递解析
    compileOnly("com.gtnewhorizons.retrofuturabootstrap:RetroFuturaBootstrap:1.0.12") {
        isTransitive = false
    }
    compileOnly("org.ow2.asm:asm:9.8")
    compileOnly("org.ow2.asm:asm-commons:9.8")
    compileOnly("org.ow2.asm:asm-tree:9.8")
    compileOnly("net.fabricmc:sponge-mixin:0.16.3+mixin.0.8.7")
    // 源码使用 org.apache.log4j（log4j 1.2 API），编译期用桥接层，运行时由 shade 产物提供
    compileOnly("org.apache.logging.log4j:log4j-1.2-api:2.25.2")
    // INanoCorePlugin/CoreModContext 签名引用 log4j2 Logger（运行时由 NanoForge 提供）
    compileOnly("org.apache.logging.log4j:log4j-api:2.25.2")

    // 测试与编译同版本对齐（NanoForge 运行时不进测试 classpath 的部分需要真实加载）
    // NanoForge 只需要 API/CoreModContext 类，其传递运行时依赖（legacyfabric lwjgl 等）
    // 由 NanoForge 自身部署管理，不进本项目的解析图
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
    // DCR 执行集成测试中，XStream 夹具会真实 new XStream()（与 DCR 同版本）；运行时 XStream
    // 类由 SDG wireGameLibraries 供给（见上方 gameLibraries 的 testRuntimeClasspath 扩展）。
    // 无参 new XStream()（XppDriver）构造/反序列化需要 XML Pull 实现，miko jar 不携带
    // （原版 Maven 坐标通过 xmlpull 传递依赖提供 API 类，MXParser 实现需 xpp3）；游戏官方
    // 序列化路径使用 StaxDriver（见 CampaignGameManager.getXStream），不需要 xpp3，
    // 故仅测试运行期补齐。
    testRuntimeOnly("xmlpull:xmlpull:1.1.3.1")
    testRuntimeOnly("xpp3:xpp3_min:1.1.4c")

    // named 游戏本体 jar（模块依赖，来自 SourceSector 本地仓库；附带 -sources.jar 供 IDE 索引）
    // compileOnly 声明由 SDG 插件统一装配（NAMED_REPO 模式），此处仅补齐测试 classpath
    namedGameJarBaseNames.forEach { baseName ->
        testImplementation("starsector.named:$baseName:0.98a-RC8-SNAPSHOT")
    }
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.named("jar"))
    // ModInfoJsonTest 校验 SDG 生成的发布元数据，需要完整产物布局
    dependsOn("modProduction")
    systemProperty("project.rootDir", rootProject.rootDir.absolutePath)
    // DCR 执行集成测试中的 XStream 夹具在 JDK 25 上需与游戏运行期相同的模块开放（见 launch_nanoforge_ss.sh）
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
    dependsOn(":native:assemble")
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

    // NanoForge coremod jar：shade 运行时依赖（fastutil/jctools/zstd-jni/log4j-1.2-api），
    // asm/mixin/RFB/log4j2/游戏 jar 为 compileOnly，由 NanoForge 运行时与游戏 classpath 提供
    dependsOn(configurations.runtimeClasspath, shade)
    from({
        (configurations.runtimeClasspath.get().files + shade.resolve())
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    // 依赖携带的 JPMS 模块描述必须剔除：jar 一旦带 module-info，RFB 会按命名模块
    // 加载本 jar，仅导出模块声明内的包（如 org.jctools.core 只导出 org/jctools/*），
    // 其余包全部不可见，类加载报 "Class bytes are null"（运行时已验证）。
    // META-INF/versions/** 是 multi-release 变体，RFB/LaunchClassLoader 不按 MR-jar 解析，
    // 混入只会产生重复类，一并剔除。
    exclude("module-info.class", "META-INF/versions/**")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
