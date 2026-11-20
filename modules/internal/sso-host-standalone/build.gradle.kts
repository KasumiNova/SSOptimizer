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
    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets.main { java.srcDir("src/dev/java") }
val obf by sourceSets.creating {
    java.srcDir("src/main/java")
    resources.srcDir("src/main/resources")
}
configurations[obf.compileOnlyConfigurationName].extendsFrom(configurations.compileOnly.get())
dependencies {
    implementation(project(":modules:internal:sso-remap"))
    add(obf.compileOnlyConfigurationName, project(":modules:internal:sso-obf"))
    add(obf.compileOnlyConfigurationName, "com.thoughtworks.xstream:xstream:1.4.21") { isTransitive = false }
}

tasks.jar { manifest.attributes("SSOptimizer-Namespace" to "named") }
tasks.register<Jar>("obfJar") {
    archiveClassifier.set("obf")
    from(obf.output)
    manifest.attributes("SSOptimizer-Namespace" to "obf")
}

tasks.test { useJUnitPlatform() }

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-proc:none")
}
