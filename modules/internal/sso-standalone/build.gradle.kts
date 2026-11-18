plugins { `java-library` }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

dependencies {
    implementation(project(":modules:internal:sso-remap"))
    implementation("com.google.code.gson:gson:2.13.1")
    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

tasks.processResources {
    from(rootProject.file("packaging/standalone/libraries.lock")) { into("ssoptimizer/standalone") }
}

tasks.jar {
    archiveBaseName.set("ssoptimizer-standalone")
    manifest.attributes("Premain-Class" to "ssoptimizer.standalone.bootstrap.PrepareAgent",
        "Main-Class" to "ssoptimizer.standalone.launch.Main")
}
