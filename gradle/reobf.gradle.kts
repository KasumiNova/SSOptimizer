import org.gradle.api.tasks.bundling.Jar
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import java.util.zip.GZIPInputStream

val abi = providers.gradleProperty("standalone.gameAbi").getOrElse("windows-rc8")
require(abi.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "无效的游戏 ABI：$abi" }
val profile = rootProject.file("packaging/abi/$abi.properties")
val buildProfile = rootProject.file("tools/reobf/abi/$abi.properties")
val mapping = rootProject.file(providers.gradleProperty("standalone.mapping").getOrElse("../Paragon/build/mappings/mappings-named.tiny"))
val gameDir = rootProject.file(providers.gradleProperty("standalone.obfGameDir").getOrElse("../SourceSector/game-jars/windows"))
val namedDir = rootProject.file(providers.gradleProperty("standalone.namedGameDir").getOrElse("../SourceSector/build/named-game-jars/windows"))
val coreNames = listOf("starfarer_obf.jar", "starfarer.api.jar", "fs.common_obf.jar", "fs.sound_obf.jar")
val game = coreNames.map(gameDir::resolve)
val named = coreNames.map(namedDir::resolve)
val output = layout.buildDirectory.dir("reobf/$abi")

fun sha256(file: File, decompressGzip: Boolean = false): String {
    val digest = MessageDigest.getInstance("SHA-256")
    try {
        file.inputStream().buffered().use { stream ->
            val gzip = if (decompressGzip) {
                stream.mark(2)
                val present = stream.read() == 0x1f && stream.read() == 0x8b
                stream.reset()
                present
            } else {
                false
            }
            DigestInputStream(if (gzip) GZIPInputStream(stream) else stream, digest).use { input ->
                input.transferTo(OutputStream.nullOutputStream())
            }
        }
    } catch (failure: IOException) {
        throw GradleException("无法计算 SHA-256：$file", failure)
    }
    return HexFormat.of().formatHex(digest.digest())
}

val resolveAbi = tasks.register("resolveReleaseAbi") {
    group = "distribution"
    inputs.file(profile).withPropertyName("releaseProfile")
    inputs.file(buildProfile).withPropertyName("buildProfile")
    inputs.file(mapping).withPropertyName("privateMapping")
    inputs.files(game).withPropertyName("obfGameJars")
    inputs.files(named).withPropertyName("namedGameJars")
    val destination = output.map { it.file("abi.properties") }
    outputs.file(destination)
    doLast {
        val declared = Properties().apply { profile.inputStream().use { load(it) } }
        require(declared.getProperty("abi") == abi) { "ABI 标识与文件名不符：$profile" }
        val accepted = (0 until declared.getProperty("inputs.count").toInt()).map { declared.getProperty("inputs.$it") }
        val hashes = game.associateWith { sha256(it) }
        if (hashes.values.joinToString(" ") !in accepted) {
            val actual = hashes.entries.joinToString("\n") { (file, hash) -> "$file: $hash" }
            throw GradleException("构建用原版游戏归档与 $abi 不符：\n$actual")
        }

        val locked = Properties().apply { buildProfile.inputStream().use { load(it) } }
        fun verify(key: String, file: File, decompressGzip: Boolean = false) {
            val expected = locked.getProperty(key)
                ?: throw GradleException("构建 ABI 配置 $buildProfile 缺少 $key")
            val actual = sha256(file, decompressGzip)
            if (actual != expected) {
                throw GradleException("构建用 $key 与 $abi 不符：$file\n预期 SHA-256：$expected\n实际 SHA-256：$actual")
            }
        }
        verify("mapping.sha256", mapping, decompressGzip = true)
        named.forEach { verify("named.${it.name}", it) }

        profile.copyTo(destination.get().asFile.apply { parentFile.mkdirs() }, overwrite = true)
    }
}
val toolClasspath = configurations.create("releaseReobfTool") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}
dependencies.add(toolClasspath.name, project(":tools:reobf"))
val app = project(":modules:internal:sso-app")
val host = project(":modules:internal:sso-host-standalone")
val jobs = listOf(Triple("Payload", app, "standalonePayload"), Triple("Host", host, "obfJar"), Triple("Coremod", app, "jar")).map { (kind, component, jarTask) ->
    val input = provider { component.tasks.named<Jar>(jarTask).get().archiveFile.get().asFile }
    val dest = output.map { it.file("${kind.lowercase()}.jar").asFile }
    val libraries = provider {
        val namedLibraries = namedDir.listFiles().orEmpty().filter { it.extension == "jar" && it.name !in coreNames }
        val hostLibraries = configurations.getByName("standaloneObfSystemLibraries").files + configurations.getByName("standaloneObfGameLibraries").files
        (namedLibraries + hostLibraries).distinct().sortedBy { it.name }
    }
    tasks.register<JavaExec>("reobf$kind") {
        group = "distribution"
        dependsOn("${component.path}:$jarTask", resolveAbi)
        classpath(toolClasspath)
        mainClass.set("github.kasuminova.ssoptimizer.reobf.ReobfMain")
        maxHeapSize = "3g"
        inputs.files(mapping, input, named, game, libraries)
        outputs.file(dest)
        doFirst {
            val arguments = mutableListOf("--mapping", mapping.absolutePath, "--input", input.get().absolutePath, "--output", dest.get().absolutePath)
            for (path in named) arguments.addAll(listOf("--named", path.absolutePath))
            for (path in game) arguments.addAll(listOf("--game", path.absolutePath))
            for (path in libraries.get()) arguments.addAll(listOf("--classpath", path.absolutePath))
            args(arguments)
        }
    }
}
val reobfRelease = tasks.register("reobfRelease") {
    group = "distribution"
    description = "生成与原版游戏 ABI 匹配的 obf 归档"
    dependsOn(jobs)
}

tasks.register<JavaExec>("verifyObfLinks") {
    group = "verification"
    description = "校验最终 obf 归档对游戏类、字段、方法的静态引用"
    dependsOn(reobfRelease)
    classpath(toolClasspath)
    mainClass.set("github.kasuminova.ssoptimizer.reobf.VerifyObf")
    maxHeapSize = "2g"
    val products = output.map { dir -> listOf("payload.jar", "host.jar", "coremod.jar").map { dir.file(it).asFile } }
    val dependencies = files(configurations.named("standaloneObfSystemLibraries"), configurations.named("standaloneObfGameLibraries"),
        provider { namedDir.listFiles().orEmpty().filter { it.extension == "jar" && it.name !in coreNames }.sortedBy { it.name } })
    inputs.files(game, products, dependencies)
    doFirst {
        val arguments = mutableListOf<String>()
        for (path in game) arguments.addAll(listOf("--game", path.absolutePath))
        for (path in dependencies) arguments.addAll(listOf("--classpath", path.absolutePath))
        for (path in products.get()) arguments.addAll(listOf("--input", path.absolutePath))
        args(arguments)
    }
}

val hostIsWindows = System.getProperty("os.name").lowercase().contains("windows")
val qaPlatform = providers.gradleProperty("ssoptimizer.native.target").orElse(providers.gradleProperty("starsector.platform"))
    .orElse(if (hostIsWindows) "windows" else "linux").get().lowercase()
val qaGame = providers.gradleProperty("standalone.verifyGame").orElse(providers.environmentVariable("SSOPTIMIZER_VERIFY_GAME"))
val python = providers.gradleProperty("standalone.python").getOrElse(if (hostIsWindows) "python" else "python3")
tasks.register<Exec>("verifyObfRelease") {
    group = "verification"
    description = "在两种渲染模式下用发行包的 RFB 和 Mixin 织入原版游戏类"
    dependsOn("stageStandalone${qaPlatform.replaceFirstChar(Char::uppercase)}Obf")
    val script = rootProject.file("tools/standalone-validation/verify_obf_mixins.py")
    doFirst {
        val installation = qaGame.orNull ?: throw GradleException("请以 -Pstandalone.verifyGame=<RC8 游戏安装> 指定实际织入校验输入")
        val stage = layout.buildDirectory.dir("standalone/$qaPlatform/obf/version").get().asFile
        val reports = layout.buildDirectory.dir("reports/obf-weave").get().asFile.toPath()
        java.nio.file.Files.createDirectories(reports)
        val report = java.nio.file.Files.createTempDirectory(reports, "$abi-")
        logger.lifecycle("实际织入报告：$report")
        commandLine(python, script.absolutePath, "--stage", stage.absolutePath, "--game", rootProject.file(installation).absolutePath,
            "--java-home", System.getProperty("java.home"), "--out", report.toString())
    }
}
