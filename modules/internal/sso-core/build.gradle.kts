plugins {
    id("ssoptimizer-module")
}

dependencies {
    api(project(":modules:api:sso-api"))
    implementation("tools.profiler:async-profiler:4.5")
    implementation("org.jctools:jctools-core:4.0.5")
}

tasks.named<JavaCompile>("compileJava") {
    options.headerOutputDirectory.set(layout.projectDirectory.dir("../../../native-headers/generated"))
}
