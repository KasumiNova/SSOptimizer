plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

dependencies {
    // AsmClassProcessor 是 byte[]->byte[] 的 @FunctionalInterface，无任何导入；
    // ExternalModOptimizer 仅引用 AsmClassProcessor。故本模块不需要 ASM 依赖。
    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.0")
}

tasks.test {
    useJUnitPlatform()
}
