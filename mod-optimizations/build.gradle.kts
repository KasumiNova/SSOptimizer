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
    implementation(project(":agent-api"))

    // 处理器仅用类的 JVM 内部名字符串 + String 描述符做 ASM 注入，无需 named-game-jars 编译依赖。
    implementation("org.ow2.asm:asm:9.9.1")
    implementation("org.ow2.asm:asm-commons:9.9.1")
    implementation("org.ow2.asm:asm-tree:9.9.1")
    // 压缩层 Deflater(9) -> Zstd，复刻 :app common/save/TerrainTileCompressionHelper。
    implementation("com.github.luben:zstd-jni:1.5.7-3")

    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.0")
    testImplementation("org.ow2.asm:asm-tree:9.9.1")
    // 字节码良构校验（patch 有效性证明）。
    testImplementation("org.ow2.asm:asm-util:9.9.1")
    // 执行集成测试中，SerializationManager 夹具的 getSerializer() 返回真实 XStream（与 DCR 同版本）。
    testImplementation("com.thoughtworks.xstream:xstream:1.4.10")
    // 夹具 SerializationManager.log 字段（合成 flush 的 catch 分支引用）。
    testImplementation("log4j:log4j:1.2.17")
}

tasks.test {
    useJUnitPlatform()
    // 执行集成测试中的 XStream 夹具在 JDK 25 上需与游戏运行期相同的模块开放（见 launch-config.json）。
    jvmArgs(
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.ref=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED"
    )
}
