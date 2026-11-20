import org.gradle.api.tasks.bundling.Jar
val abi = providers.gradleProperty("standalone.gameAbi").getOrElse("windows-rc8")
require(abi.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "无效的游戏 ABI：$abi" }
val mapping = rootProject.file(providers.gradleProperty("standalone.mapping").getOrElse("../Paragon/build/mappings/mappings-named.tiny"))
val gameDir = rootProject.file(providers.gradleProperty("standalone.obfGameDir").getOrElse("../SourceSector/game-jars/windows"))
val namedDir = rootProject.file(providers.gradleProperty("standalone.namedGameDir").getOrElse("../SourceSector/build/named-game-jars/windows"))
val coreNames = listOf("starfarer_obf.jar", "starfarer.api.jar", "fs.common_obf.jar", "fs.sound_obf.jar")
val game = coreNames.map(gameDir::resolve)
val named = coreNames.map(namedDir::resolve)
val output = layout.buildDirectory.dir("reobf/$abi")

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
        dependsOn("${component.path}:$jarTask")
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
tasks.register("reobfRelease") {
    group = "distribution"
    description = "生成与原版游戏 ABI 匹配的 obf 归档"
    dependsOn(jobs)
}
