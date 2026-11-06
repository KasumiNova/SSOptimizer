plugins {
    id("ssoptimizer-module")
}

dependencies {
    implementation(project(":modules:internal:sso-core"))
    implementation(project(":modules:internal:sso-render"))
    implementation("com.github.luben:zstd-jni:1.5.7-3")

    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.0")
    testImplementation("org.ow2.asm:asm-tree:9.8")
    testImplementation("starsector.named:fs.common_obf:0.98a-RC8-SNAPSHOT")
}

tasks.named<JavaCompile>("compileJava") {
    options.headerOutputDirectory.set(layout.projectDirectory.dir("../../../native-headers/generated"))
}

tasks.test {
    useJUnitPlatform()
}
