plugins {
    application
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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.0")
    testImplementation("log4j:log4j:1.2.17")

    implementation("org.ow2.asm:asm:9.9.1")
    implementation("org.ow2.asm:asm-commons:9.9.1")
    implementation("org.ow2.asm:asm-tree:9.9.1")
    implementation("it.unimi.dsi:fastutil:8.5.18")
    implementation("net.fabricmc:sponge-mixin:0.15.4+mixin.0.8.7")
    implementation("com.github.luben:zstd-jni:1.5.7-3")
    compileOnly("log4j:log4j:1.2.17")

    val starsectorGameDir = providers.gradleProperty("starsector.gameDir").orNull
    if (starsectorGameDir != null) {
        val gameDir = file(starsectorGameDir)
        compileOnly(fileTree(gameDir) { include("*.jar") })
        testImplementation(fileTree(gameDir) { include("*.jar") })
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("project.rootDir", rootProject.rootDir.absolutePath)
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
    val measurementTime = providers.gradleProperty("jmhMeasurementTime").orElse("300ms")
    val forks = providers.gradleProperty("jmhForks").orElse("1")
    val timeUnit = providers.gradleProperty("jmhTimeUnit").orElse("us")
    val nativeLibraryPath = providers.gradleProperty("jmhNativePath").orElse(
        layout.projectDirectory.file("../native/build/lib/main/debug/${System.mapLibraryName("native")}").asFile.absolutePath
    )
    args(
        includePattern.get(),
        "-wi", warmupIterations.get(),
        "-i", measurementIterations.get(),
        "-f", forks.get(),
        "-w", warmupTime.get(),
        "-r", measurementTime.get(),
        "-bm", "avgt",
        "-tu", timeUnit.get(),
        "-jvmArgsAppend", "-Dssoptimizer.native.path=${nativeLibraryPath.get()}"
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
