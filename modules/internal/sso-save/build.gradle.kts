import java.security.MessageDigest
import java.util.HexFormat

plugins {
    id("ssoptimizer-module")
}

dependencies {
    implementation(project(":modules:internal:sso-core"))
    implementation("it.unimi.dsi:fastutil:8.5.18")
    implementation("org.jctools:jctools-core:4.0.5")
    implementation("com.github.luben:zstd-jni:1.5.7-3")
}

val xstreamCompatibilityTestSourceSet = sourceSets.create("xstreamCompatibilityTest") {
    java.srcDir("src/xstreamCompatibilityTest/java")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

dependencies {
    "xstreamCompatibilityTestImplementation"(platform("org.junit:junit-bom:5.13.0"))
    "xstreamCompatibilityTestImplementation"("org.junit.jupiter:junit-jupiter")
    "xstreamCompatibilityTestImplementation"("com.thoughtworks.xstream:xstream:1.4.21")
    "xstreamCompatibilityTestImplementation"(project(":modules:api:sso-api"))
    "xstreamCompatibilityTestImplementation"("org.ow2.asm:asm:9.8")
    "xstreamCompatibilityTestRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.13.0")
    "xstreamCompatibilityTestRuntimeOnly"("log4j:log4j:1.2.17")
}

tasks.register<Test>("xstreamCompatibilityTest") {
    group = "verification"
    description = "Verify save type rules and Java 25 lambda classification against upstream XStream"
    testClassesDirs = xstreamCompatibilityTestSourceSet.output.classesDirs
    classpath = xstreamCompatibilityTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    jvmArgs("--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED")
    doFirst {
        val upstream = configurations.named("xstreamCompatibilityTestRuntimeClasspath").get()
            .resolvedConfiguration.resolvedArtifacts.single {
                it.moduleVersion.id.group == "com.thoughtworks.xstream" && it.name == "xstream"
            }.file
        val digest = MessageDigest.getInstance("SHA-256").digest(upstream.readBytes())
        logger.lifecycle("XStream compatibility runtime: ${upstream.absolutePath}, SHA-256=${HexFormat.of().formatHex(digest)}")
    }
}

tasks.named("check") {
    dependsOn("xstreamCompatibilityTest")
}

val saveBenchSourceSet = sourceSets.create("saveBench") {
    java.srcDir("src/saveBench/java")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

dependencies {
    "saveBenchImplementation"("tools.profiler:async-profiler:4.5")
}

configurations.named("saveBenchImplementation") {
    extendsFrom(configurations.implementation.get(), configurations.compileOnly.get())
}
configurations.named("saveBenchRuntimeOnly") {
    extendsFrom(configurations.runtimeOnly.get())
}
// gameLibraries 由 SDG 在 afterEvaluate 中创建，继承关系必须延后配置。
project.afterEvaluate {
    configurations.named("saveBenchRuntimeClasspath") {
        extendsFrom(configurations.named("gameLibraries").get())
    }
    configurations.named("saveBenchCompileClasspath") {
        extendsFrom(configurations.named("gameLibraries").get())
    }
}

val namedGameJarBaseNames = listOf("starfarer_obf", "starfarer.api", "fs.common_obf", "fs.sound_obf")

// named jar 中混淆器遗留的非法字段名会触发 JDK 25 ClassFormatError，离线基准必须先清洗副本。
val saveBenchGameJars = configurations.create("saveBenchGameJars") {
    isCanBeConsumed = false
    isVisible = false
}
val gameVersion = providers.gradleProperty("starsector.gameVersion").orElse("0.98a-RC8").get()
namedGameJarBaseNames.forEach { baseName ->
    dependencies {
        add("saveBenchGameJars", "starsector.named:$baseName:$gameVersion-SNAPSHOT")
    }
}

val sanitizedGameJarsDir = layout.buildDirectory.dir("savebench-sanitized-jars")

tasks.register<JavaExec>("sanitizeNamedJarsForBench") {
    group = "benchmark"
    description = "Sanitize illegal member names in named game jars for offline saveBench classloading"
    dependsOn(tasks.named(saveBenchSourceSet.classesTaskName))
    val gameJars = saveBenchGameJars.incoming.artifactView { lenient(false) }.files
    inputs.files(gameJars)
    outputs.dir(sanitizedGameJarsDir)
    classpath = saveBenchSourceSet.runtimeClasspath
    mainClass.set("github.kasuminova.ssoptimizer.savebench.JarSanitizerMain")
    args(sanitizedGameJarsDir.get().asFile.absolutePath)
    gameJars.forEach { args(it.absolutePath) }
}

tasks.register<JavaExec>("saveBench") {
    group = "benchmark"
    description = "Offline save serialization benchmark on a real save (no game process)"
    dependsOn(tasks.named("sanitizeNamedJarsForBench"))
    classpath = saveBenchSourceSet.runtimeClasspath.filter { file ->
        namedGameJarBaseNames.none { baseName -> file.name.startsWith(baseName) }
    } + fileTree(sanitizedGameJarsDir) { include("*.jar") }
    mainClass.set("github.kasuminova.ssoptimizer.savebench.SaveBenchMain")
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
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "-Xmx4g"
    )
    val gameDir = providers.gradleProperty("saveBench.gameDir").orElse("/mnt/store/Games/Starsector098-linux").get()
    systemProperty("sso.savebench.gameDir", gameDir)
    systemProperty("java.library.path", "$gameDir/native/linux")
    val saveDir = providers.gradleProperty("saveBench.saveDir").orNull
        ?: throw GradleException("saveBench 需要 -PsaveBench.saveDir=<存档目录>（绝对路径或 saves/ 下的目录名）")
    systemProperty("sso.savebench.saveDir", saveDir)
    systemProperty("sso.savebench.mode", providers.gradleProperty("saveBench.mode").orElse("roundtrip").get())
    systemProperty("sso.savebench.profile", providers.gradleProperty("saveBench.profile").orElse("true").get())
    systemProperty("sso.savebench.outputDir", providers.gradleProperty("saveBench.outputDir")
        .orElse(layout.buildDirectory.dir("savebench-output").get().asFile.absolutePath).get())
}
