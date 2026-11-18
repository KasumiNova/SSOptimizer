import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.security.MessageDigest
import java.util.HexFormat

val sources = rootProject.file("packaging/standalone")
val base = layout.buildDirectory.dir("standalone")
val versionId = providers.gradleProperty("standalone.version").orElse(project.version.toString()).get()
require(versionId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")) && !versionId.endsWith('.') &&
        !Regex("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?").matches(versionId)) { "非法版本 ID：$versionId" }
val os = System.getProperty("os.name").lowercase()
val target = providers.gradleProperty("ssoptimizer.native.target").orElse(providers.gradleProperty("starsector.platform"))
    .orElse(if (os.contains("windows")) "windows" else "linux").get().lowercase()
val nativeModules = mapOf("font" to "sso-font:native-font", "ime" to "sso-ime:native-ime", "render" to "sso-render:native-render",
    "loading" to "sso-loading:native-loading", "texcompress" to "sso-loading:native-texcompress")
if (target == "windows" && os.contains("linux")) {
    project(":modules:internal:sso-font:native-font").run {
        pluginManager.withPlugin("ssoptimizer-native-module") {
            // MinGW 静态 FreeType 的 PNG 解码器引用 libpng 和 zlib。
            extensions.getByType<SsoNativeExtension>().libpng.set(true)
        }
    }
}

val lockFile = sources.resolve("libraries.lock")
val libraries = lockFile.readLines().map { it.split(' ') }
repositories {
    maven { url = uri("https://maven.fabricmc.net/"); content { includeModule("net.fabricmc", "tiny-remapper") } }
    maven { url = uri("https://nexus.gtnewhorizons.com/repository/releases/"); content { includeGroup("com.gtnewhorizons.retrofuturabootstrap") } }
}
val locked = listOf("lib", "game-lib").associateWith { domain ->
    configurations.create(if (domain == "lib") "standaloneSystemLibraries" else "standaloneGameLibraries") {
        isCanBeResolved = true
        isCanBeConsumed = false
        isTransitive = false
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
            attribute(org.gradle.api.attributes.java.TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(org.gradle.api.attributes.java.TargetJvmEnvironment.STANDARD_JVM))
        }
    }.also { config -> libraries.filter { it[0] == domain }.forEach { dependencies.add(config.name, it[2] + "@jar") } }
}
fun digest(file: File): String = file.inputStream().buffered().use { stream ->
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(131072)
    while (true) { val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
    HexFormat.of().formatHex(hash.digest())
}
val helper = sources.resolve("ReleaseFiles.java")
fun Task.releaseTool(vararg args: String) {
    val java = File(System.getProperty("java.home"), if (os.contains("windows")) "bin/java.exe" else "bin/java")
    val process = ProcessBuilder(listOf(java.absolutePath, helper.absolutePath) + args).redirectErrorStream(true).start()
    val diagnostic = process.inputStream.bufferedReader().use { it.readText() }
    val status = process.waitFor()
    if (status != 0) throw GradleException("发行校验程序 ${args.first()} 退出：$status\n$diagnostic")
}
val mapping = rootProject.file(providers.gradleProperty("standalone.mapping").getOrElse("../Paragon/build/mappings/mappings-named.tiny"))
val mapOut = base.map { it.file("prepared/mappings/game-full.tiny.gz").asFile }
val prepareMapping = tasks.register("prepareStandaloneMapping") {
    inputs.files(mapping, helper); outputs.file(mapOut)
    doLast { releaseTool("mapping", mapping.absolutePath, mapOut.get().absolutePath) }
}
val jdkHome = providers.gradleProperty("standalone.windowsJdk").orNull?.let(rootProject::file)
val jdkZip = providers.gradleProperty("standalone.windowsJdkZip").orNull?.let(rootProject::file)
val jdkUrl = providers.gradleProperty("standalone.windowsJdkUrl").orElse("https://cdn.azul.com/zulu/bin/zulu25.38.201-ca-jdk25.0.5.1-win_x64.zip")
val jdkOut = base.map { it.dir("prepared/runtime").asFile }
val prepareJdk = tasks.register("prepareStandaloneWindowsJdk") {
    inputs.file(helper); inputs.property("source", jdkUrl); jdkHome?.let { inputs.dir(it) }; jdkZip?.let { inputs.file(it) }; outputs.dir(jdkOut)
    doLast {
        require(jdkHome == null || jdkZip == null) { "standalone.windowsJdk 与 standalone.windowsJdkZip 只能指定一项" }
        val cache = gradle.gradleUserHomeDir.resolve("caches/ssoptimizer/zulu-25.0.5.1-win-x64.zip")
        releaseTool("jdk", jdkOut.get().absolutePath, jdkHome?.absolutePath ?: "", jdkZip?.absolutePath ?: "", jdkUrl.get(), cache.absolutePath, gradle.startParameter.isOffline.toString())
    }
}
fun moduleArchive(path: String, task: String) = provider {
    val archive = project(":modules:internal:$path").tasks.getByName(task) as AbstractArchiveTask
    archive.archiveFile.get().asFile
}
val payload = moduleArchive("sso-app", "standalonePayload")
val agent = moduleArchive("sso-standalone", "jar")
val host = moduleArchive("sso-host-standalone", "jar")
val remap = moduleArchive("sso-remap", "jar")
val stageTasks = listOf("linux", "windows").associateWith { platform ->
    val windows = platform == "windows"
    val output = base.map { it.dir("$platform/version") }
    tasks.register<Sync>(if (windows) "stageStandaloneWindows" else "stageStandaloneLinux") {
        group = "distribution"
        description = "准备 Standalone $platform 的独立版本目录"
        dependsOn(prepareMapping, ":modules:internal:sso-app:standalonePayload", ":modules:internal:sso-standalone:jar", ":modules:internal:sso-host-standalone:jar", ":modules:internal:sso-remap:jar")
        inputs.files(lockFile, helper); inputs.property("versionId", versionId); inputs.property("nativeTarget", target)
        into(output); duplicatesStrategy = DuplicatesStrategy.FAIL
        from(payload) { rename { "payload.jar" } }; from(agent) { rename { "agent.jar" } }
        from(listOf(host, remap)) { into("lib") }; from(mapOut) { into("mappings") }
        from(lockFile); from(sources.resolve("mod_info.json")) { into("mod"); expand("version" to versionId) }
        from(rootProject.file("game-fonts/ttf")) { into("mod/fonts"); include("*.ttf") }
        if (windows) { dependsOn(prepareJdk); from(jdkOut) { into("runtime") } }
        else { from(sources.resolve("launch.sh")) { filePermissions { unix("rwxr-xr-x") } } }
        for ((domain, config) in locked) from(config) { into(domain) }
        for ((module, path) in nativeModules) {
            val sub = project(":modules:internal:$path")
            dependsOn("${sub.path}:" + if (windows && os.contains("linux")) "linkWindowsCrossSharedLibrary" else "assembleRelease")
            val nativeFile = sub.layout.buildDirectory.file("lib/main/release/" + if (windows) "ssoptimizer_$module.dll" else "libssoptimizer_$module.so")
            from(nativeFile) { into("native/$platform") }
        }
        from(provider {
            nativeModules.values.flatMap { path ->
                val ext = project(":modules:internal:$path").extensions.extraProperties
                @Suppress("UNCHECKED_CAST")
                (if (windows) ext["windowsRuntimeDlls"] as List<File> else (ext["linuxRuntimeSharedLibs"] as Provider<List<File>>).get())
            }.distinct()
        }) { into("native/$platform") }
        doFirst {
            require(platform == target) { "构建 $platform 包时请设置 -Pssoptimizer.native.target=$platform" }
            for (artifact in listOf(payload, agent, host, remap)) require(artifact.get().isFile) { "缺少项目归档：${artifact.get()}" }
            require(rootProject.file("game-fonts/ttf").listFiles()?.any { it.extension == "ttf" } == true) { "缺少模组字体" }
            for ((domain, config) in locked) {
                val artifacts = config.resolvedConfiguration.resolvedArtifacts.associateBy { it.moduleVersion.id.toString() }
                val expected = libraries.filter { it[0] == domain }
                require(artifacts.keys == expected.map { it[2] }.toSet()) { "意外的 $domain 解析结果：${artifacts.keys}" }
                expected.forEach { (_, hash, coordinate) ->
                    val f = artifacts.getValue(coordinate).file
                    require(digest(f) == hash) { "构件摘要不符：$coordinate ($f)" }
                }
            }
        }
        doLast { releaseTool("audit", output.get().asFile.absolutePath, platform) }
    }
}
val signKey = providers.gradleProperty("standalone.signingKey").orElse(providers.environmentVariable("SSOPTIMIZER_SIGNING_KEY"))
signKey.orNull?.let { key ->
    val path = rootProject.file(key).canonicalFile.toPath()
    require(!path.startsWith(rootProject.rootDir.canonicalFile.toPath()) && !path.startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath())) {
        "签名私钥必须位于仓库和构建输出之外：$path"
    }
}
val metadata = base.map { it.dir("windows/signed").asFile }
val signRelease = tasks.register("signStandaloneWindows") {
    dependsOn(stageTasks.getValue("windows")); outputs.upToDateWhen { false }
    doLast {
        val key = signKey.orNull ?: throw GradleException("Windows 发布需要发行者 RSA PKCS#8 私钥：-Pstandalone.signingKey=<文件>")
        releaseTool("sign", base.get().dir("windows/version").asFile.absolutePath, metadata.get().absolutePath, sources.resolve("windows").absolutePath, versionId, rootProject.file(key).absolutePath)
    }
}
tasks.register<Zip>("packageStandaloneWindows") {
    group = "distribution"; description = "构建含签名清单和完整 Java 25 的 Windows x64 安装包"
    dependsOn(signRelease); archiveFileName.set("SSOptimizer-Standalone-$versionId-windows-x64.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions")); isReproducibleFileOrder = true; isPreserveFileTimestamps = false
    from(base.map { it.dir("windows/version") }) { into("version") }
    from(metadata) { include("Install.cmd", "version/release.manifest", "version/release.sig", "version/tools/Launch.cmd", "version/tools/Manage.cmd", "version/tools/Manage.ps1") }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}
