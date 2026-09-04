plugins {
    id("ssoptimizer-module")
}

// 渲染线程模块：LWJGL 固定管线 bridge 镜像、渲染线程命令队列、状态仿真、
// sprite/引擎/粒子/护盾等渲染优化与相关 Mixin（native GL 族子模块在阶段 4 接入）。
dependencies {
    implementation(project(":modules:internal:sso-core"))
    implementation("it.unimi.dsi:fastutil:8.5.18")
    implementation("org.jctools:jctools-core:4.0.5")

    testImplementation(platform("org.junit:junit-bom:5.13.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.0")

    // 尾迹 helper 单测直接实例化真实游戏数据类（ContrailEngineV2.Contrail/
    // ContrailPoint 为纯数据类，无引擎运行时耦合）；named 仓由 SDG 插件注册，
    // compileOnly 声明由其统一装配，此处仅补齐测试 classpath（与 sso-app 同模式）。
    listOf("starfarer_obf", "starfarer.api", "fs.common_obf", "fs.sound_obf").forEach { baseName ->
        testImplementation("starsector.named:$baseName:0.98a-RC8-SNAPSHOT")
    }
    // Vector2f 所在的 lwjgl_util（gameLibraries 仅挂 compileOnly，测试运行期需真实加载）
    testImplementation("starsector.game:lwjgl_util:0.98a-RC8-SNAPSHOT")
}

tasks.named<JavaCompile>("compileJava") {
    // JNI 桥接类（SpriteBatchNative/EngineBatchNative 等）在本模块：导出头文件到根级共享目录（阶段 4 起各 native 子模块共用）
    options.headerOutputDirectory.set(layout.projectDirectory.dir("../../../native-headers/generated"))
}

tasks.test {
    useJUnitPlatform()
}
