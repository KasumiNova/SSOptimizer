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

sourceSets.main { java.srcDir("src/dev/java") }
val obf by sourceSets.creating {
    java.srcDir("src/main/java")
    java.exclude("**/HistoricalSave.java", "**/instance/Save*.java", "**/ModArchiveLayout.java")
    resources.srcDir("src/main/resources")
}

dependencies {
    add(obf.implementationConfigurationName, project(":modules:internal:sso-obf"))
    add(obf.implementationConfigurationName, "com.google.code.gson:gson:2.13.1")
}

tasks.test { useJUnitPlatform() }

tasks.processResources {
    from(rootProject.file("packaging/standalone/libraries.lock")) { into("ssoptimizer/standalone") }
}

tasks.jar {
    archiveBaseName.set("ssoptimizer-standalone")
    manifest.attributes("Premain-Class" to "ssoptimizer.standalone.bootstrap.PrepareAgent",
        "Main-Class" to "ssoptimizer.standalone.launch.Main", "SSOptimizer-Namespace" to "named")
}

tasks.register<Jar>("obfJar") {
    archiveBaseName.set("ssoptimizer-standalone")
    archiveClassifier.set("obf")
    from(obf.output)
    from(rootProject.file("packaging/standalone/libraries-obf.lock")) {
        into("ssoptimizer/standalone")
        rename { "libraries.lock" }
    }
    manifest.attributes("Premain-Class" to "ssoptimizer.standalone.bootstrap.PrepareAgent",
        "Main-Class" to "ssoptimizer.standalone.launch.Main", "SSOptimizer-Namespace" to "obf")
}
