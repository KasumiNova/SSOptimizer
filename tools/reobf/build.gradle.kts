plugins { application }

java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
application.mainClass.set("github.kasuminova.ssoptimizer.reobf.ReobfMain")

repositories { maven("https://maven.fabricmc.net/") }

dependencies {
    implementation(project(":modules:internal:sso-obf"))
    implementation("net.fabricmc:tiny-remapper:0.14.0")
    implementation("net.fabricmc:mapping-io:0.8.0")
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-commons:9.10.1")
    implementation("org.ow2.asm:asm-tree:9.10.1")
    implementation("org.ow2.asm:asm-analysis:9.10.1")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}
