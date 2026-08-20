plugins {
    id("ssoptimizer-module")
}

// 存档模块：XStream 序列化/反序列化优化（双向计划缓存、引用 ID、XML 写入队列等），
// 以及离线存档基准 saveBench 源集（不开游戏进程的 fromXML/toXML/roundtrip 基准）。
dependencies {
    implementation(project(":modules:internal:sso-core"))
    implementation("it.unimi.dsi:fastutil:8.5.18")
    implementation("org.jctools:jctools-core:4.0.5")
    implementation("com.github.luben:zstd-jni:1.5.7-3")
}

val saveBenchSourceSet = sourceSets.create("saveBench") {
    java.srcDir("src/saveBench/java")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

dependencies {
    // saveBench 的 BenchmarkProfiler 签名暴露 async-profiler 类型
    "saveBenchImplementation"("tools.profiler:async-profiler:4.5")
}

// saveBench 离线基准需要游戏第三方 jar（janino/txw2/stax 等）与 named 游戏 jar
configurations.named("saveBenchImplementation") {
    extendsFrom(configurations.implementation.get(), configurations.compileOnly.get())
}
configurations.named("saveBenchRuntimeOnly") {
    extendsFrom(configurations.runtimeOnly.get())
}
// gameLibraries 由 SDG 插件在 afterEvaluate 阶段创建，extendsFrom 须同样推迟
project.afterEvaluate {
    configurations.named("saveBenchRuntimeClasspath") {
        extendsFrom(configurations.named("gameLibraries").get())
    }
    configurations.named("saveBenchCompileClasspath") {
        extendsFrom(configurations.named("gameLibraries").get())
    }
}

val namedGameJarBaseNames = listOf("starfarer_obf", "starfarer.api", "fs.common_obf", "fs.sound_obf")

// saveBench 离线基准的游戏 jar 解析配置：named jar 中混淆器遗留的非法字段名
// （如 "String.new"）会被 JDK 25 拒绝加载（ClassFormatError），运行期游戏靠 NanoForge
// remap 回退 intermediary 名规避；离线环境需在构建期先清洗出合法副本。
// 详见 savebench/JarSanitizerMain 的设计注释。
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
    // 只用到 asm 与 zip IO，不会加载游戏类，可直接复用 saveBench classpath
    classpath = saveBenchSourceSet.runtimeClasspath
    mainClass.set("github.kasuminova.ssoptimizer.savebench.JarSanitizerMain")
    args(sanitizedGameJarsDir.get().asFile.absolutePath)
    gameJars.forEach { args(it.absolutePath) }
}

tasks.register<JavaExec>("saveBench") {
    group = "benchmark"
    description = "Offline save serialization benchmark on a real save (no game process)"
    dependsOn(tasks.named("sanitizeNamedJarsForBench"))
    // 用清洗后的游戏 jar 副本替换 classpath 上的原始 named jar（按基名匹配）
    classpath = saveBenchSourceSet.runtimeClasspath.filter { file ->
        namedGameJarBaseNames.none { baseName -> file.name.startsWith(baseName) }
    } + fileTree(sanitizedGameJarsDir) { include("*.jar") }
    mainClass.set("github.kasuminova.ssoptimizer.savebench.SaveBenchMain")
    // 与游戏运行期一致：游戏 jar 内混淆器产物的 StackMapTable 有损坏帧，靠 -noverify 跳过校验
    jvmArgs("-noverify")
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
    // -PsaveBench.gameDir：游戏根目录（提供 saves/mods/starsector-core 与路径属性基准）
    val gameDir = providers.gradleProperty("saveBench.gameDir").orElse("/mnt/store/Games/Starsector098-linux").get()
    systemProperty("sso.savebench.gameDir", gameDir)
    // lwjgl native：与启动脚本一致指向游戏 natives 目录
    systemProperty("java.library.path", "$gameDir/native/linux")
    // -PsaveBench.saveDir：存档目录（绝对路径或 gameDir/saves 下的目录名），必填
    val saveDir = providers.gradleProperty("saveBench.saveDir").orNull
        ?: throw GradleException("saveBench 需要 -PsaveBench.saveDir=<存档目录>（绝对路径或 saves/ 下的目录名）")
    systemProperty("sso.savebench.saveDir", saveDir)
    // -PsaveBench.mode：load|save|roundtrip（默认 roundtrip）
    systemProperty("sso.savebench.mode", providers.gradleProperty("saveBench.mode").orElse("roundtrip").get())
    // -PsaveBench.profile：true|false（默认 true，async-profiler cpu 采样）
    systemProperty("sso.savebench.profile", providers.gradleProperty("saveBench.profile").orElse("true").get())
    // -PsaveBench.outputDir：摘要与 profile 输出目录（默认 build/savebench-output）
    systemProperty("sso.savebench.outputDir", providers.gradleProperty("saveBench.outputDir")
        .orElse(layout.buildDirectory.dir("savebench-output").get().asFile.absolutePath).get())
}
