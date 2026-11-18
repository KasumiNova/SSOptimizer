plugins { java }

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

repositories {
    mavenCentral()
    maven("https://nexus.gtnewhorizons.com/repository/releases/")
    maven("https://maven.fabricmc.net/")
}

dependencies {
    compileOnly("com.gtnewhorizons.retrofuturabootstrap:RetroFuturaBootstrap:1.0.12") { isTransitive = false }
    compileOnly("net.fabricmc:sponge-mixin:0.16.3+mixin.0.8.7") { isTransitive = false }
    compileOnly("io.github.llamalad7:mixinextras-common:0.5.0") { isTransitive = false }
    compileOnly("org.ow2.asm:asm-tree:9.10.1")
    compileOnly("org.apache.logging.log4j:log4j-api:2.25.2")
    compileOnly("org.json:json:20240303")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-proc:none")
}
