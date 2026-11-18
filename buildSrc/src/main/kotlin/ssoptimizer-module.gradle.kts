import io.github.nanoforged.sdg.SdgGameDepsPlugin

plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://nexus.gtnewhorizons.com/repository/releases/") }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

apply<SdgGameDepsPlugin>()

dependencies {
    compileOnly("org.ow2.asm:asm:9.8")
    compileOnly("org.ow2.asm:asm-commons:9.8")
    compileOnly("org.ow2.asm:asm-tree:9.8")
    compileOnly("net.fabricmc:sponge-mixin:0.16.3+mixin.0.8.7")
    compileOnly("org.apache.logging.log4j:log4j-1.2-api:2.25.2")
    compileOnly("org.apache.logging.log4j:log4j-api:2.25.2")
    compileOnly("org.apache.logging.log4j:log4j-core:2.25.2")
}
