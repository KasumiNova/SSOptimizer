plugins {
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

/**
 * SourceSector named 游戏 jar 本地 maven 仓（windows 仓，含 -sources.jar）。
 * 默认取同级的 SourceSector 检出；CI 用 -Psourcesector.namedRepo=<路径> 覆盖。
 */
val sourceSectorNamedRepo = providers.gradleProperty("sourcesector.namedRepo")
    .orElse(providers.provider { rootProject.file("../SourceSector/build/named-game-repo/windows").absolutePath })

/** 游戏本体 jar 基名——与 SourceSector 发布的本地仓库 artifactId 一一对应。 */
val namedGameJarBaseNames = listOf("starfarer_obf", "starfarer.api", "fs.common_obf", "fs.sound_obf")

/**
 * 游戏运行时的第三方依赖（编译/测试 classpath 视图）——与 Starsector 0.98a-RC8 运行时版本对齐。
 * 不打进 coremod jar：运行时由游戏 classpath 提供。
 */
val gameThirdParty = configurations.create("gameThirdParty") {
    isCanBeConsumed = false
}

/**
 * 需要 shade 进 coremod jar 的额外依赖（log4j 1.2 API 桥接层）。
 * 游戏自带 log4j-1.2.9.jar 已被 NanoForge 启动脚本排除，源码使用的 org.apache.log4j API
 * 由 log4j-1.2-api 桥接到 NanoForge 运行时提供的 log4j2。
 */
val shade = configurations.create("shade") {
    isCanBeConsumed = false
}

configurations.compileOnly {
    extendsFrom(gameThirdParty)
}
configurations.testImplementation {
    extendsFrom(gameThirdParty)
}

repositories {
    // NanoForge coremod API（publishToMavenLocal 产物）
    mavenLocal()
    // RFB / LaunchWrapper（IClassTransformer 编译期依赖）
    maven {
        url = uri("https://nexus.gtnewhorizons.com/repository/releases/")
    }
    // named 游戏 jar 本地仓库（SourceSector :mapping:publishNamedGameJars 发布，附带 -sources.jar）
    maven {
        url = uri(sourceSectorNamedRepo.get())
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

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(gradleTestKit())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.0")
    // 测试运行期的 org.apache.log4j 实现（生产运行期为 shade 的 log4j-1.2-api 桥接层）
    testImplementation("log4j:log4j:1.2.17")

    // ---- 打进 coremod jar 的运行时依赖（NanoForge 未提供） ----
    // :mapping 提供 GameMemberNames 运行期查表（TinyV2 仓库 + 人工映射表资源），与旧 agent jar 同款 shade；
    // 排除其 ASM 传递依赖——编译/运行期 ASM 统一对齐 NanoForge 提供的 9.8
    implementation(project(":mapping")) {
        exclude(group = "org.ow2.asm")
    }
    implementation("it.unimi.dsi:fastutil:8.5.18")
    implementation("org.jctools:jctools-core:4.0.5")
    implementation("com.github.luben:zstd-jni:1.5.7-3")
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
    // DCR 执行集成测试中，SerializationManager 夹具的 getSerializer() 返回真实 XStream（与 DCR 同版本）；
    // 使用与 gameThirdParty 相同的 miko 补丁版 jar 文件：既保证测试行为与游戏运行时一致，
    // 也避免测试 classpath 上同时出现 miko 版与原版两个 XStream 导致的重复类冲突。
    // 无参 new XStream()（XppDriver）构造/反序列化需要 XML Pull 实现，而 miko jar 文件不携带
    // （原版 Maven 坐标通过 xmlpull 传递依赖提供 API 类，MXParser 实现需 xpp3）；游戏官方
    // 序列化路径使用 StaxDriver（见 CampaignGameManager.getXStream），不需要 xpp3，
    // 故仅测试运行期补齐，gameThirdParty 编译期不引入。
    testImplementation(files("../game-jars/third-party/xstream-1.4.21_miko.jar"))
    testRuntimeOnly("xmlpull:xmlpull:1.1.3.1")
    testRuntimeOnly("xpp3:xpp3_min:1.1.4c")

    // named 游戏本体 jar（模块依赖，来自 SourceSector 本地仓库；附带 -sources.jar 供 IDE 索引）
    namedGameJarBaseNames.forEach { baseName ->
        compileOnly("starsector.named:$baseName:0.98a-RC8-SNAPSHOT")
        testImplementation("starsector.named:$baseName:0.98a-RC8-SNAPSHOT")
    }

    // 游戏运行时第三方依赖（对齐 Starsector 0.98a-RC8，compileOnly + testImplementation 继承）
    gameThirdParty("org.lwjgl.lwjgl:lwjgl:2.9.3")
    gameThirdParty("org.lwjgl.lwjgl:lwjgl_util:2.9.3")
    // XStream：游戏实际携带 miko 补丁版（FieldAliasingMapper 等内部签名与 Maven 原版不同），
    // 编译期以文件形式对齐游戏运行时 API，避免 Mixin 注入目标类签名不匹配
    gameThirdParty(files("../game-jars/third-party/xstream-1.4.21_miko.jar"))
    gameThirdParty("org.codehaus.janino:janino:2.7.8")
    gameThirdParty("org.codehaus.janino:commons-compiler:2.7.8")
    gameThirdParty("org.codehaus.janino:commons-compiler-jdk:2.7.8")
    gameThirdParty("org.json:json:20231013")
    gameThirdParty("javax.xml.bind:jaxb-api:2.4.0-b180830.0359")
    gameThirdParty("org.glassfish.jaxb:txw2:3.0.2")
    gameThirdParty("org.sejda.imageio:webp-imageio:0.1.6")
    gameThirdParty("net.java.jinput:jinput:2.0.7")
}

tasks.processResources {
    // coremod.toml 的版本号随项目版本走，避免两处维护
    filesMatching("coremod.toml") {
        expand("projectVersion" to project.version.toString())
    }
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.named("jar"))
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
