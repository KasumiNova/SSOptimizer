plugins {
    id("ssoptimizer-module")
}

dependencies {
    implementation(project(":modules:internal:sso-core"))

    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.0")
    testImplementation("org.ow2.asm:asm:9.8")
    testImplementation("org.ow2.asm:asm-tree:9.8")
    testImplementation("starsector.named:starfarer_obf:0.98a-RC8-SNAPSHOT")
}

tasks.named<JavaCompile>("compileJava") {
    options.headerOutputDirectory.set(layout.projectDirectory.dir("../../../native-headers/generated"))
}

tasks.test {
    useJUnitPlatform()
}
