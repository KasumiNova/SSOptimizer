plugins {
    id("ssoptimizer-module")
}

dependencies {
    implementation(project(":modules:internal:sso-core"))
    compileOnly("com.gtnewhorizons.retrofuturabootstrap:RetroFuturaBootstrap:1.0.12") { isTransitive = false }
    implementation("it.unimi.dsi:fastutil:8.5.18")
    implementation("org.jctools:jctools-core:4.0.5")

    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.0")

    listOf("starfarer_obf", "starfarer.api", "fs.common_obf", "fs.sound_obf").forEach { baseName ->
        testImplementation("starsector.named:$baseName:0.98a-RC8-SNAPSHOT")
    }
    testImplementation("starsector.game:lwjgl_util:0.98a-RC8-SNAPSHOT")
    testImplementation("org.ow2.asm:asm-tree:9.8")
    testRuntimeOnly("log4j:log4j:1.2.17")
}

tasks.named<JavaCompile>("compileJava") {
    options.headerOutputDirectory.set(layout.projectDirectory.dir("../../../native-headers/generated"))
}

tasks.test {
    useJUnitPlatform()
}
