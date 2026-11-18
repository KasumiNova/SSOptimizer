import java.io.DataOutputStream

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

val fontTables = rootProject.fileTree("game-fonts/fnt") { include("*.fnt") }
tasks.processResources {
    inputs.files(fontTables).withPathSensitivity(PathSensitivity.RELATIVE)
    doLast {
        val directory = destinationDir.resolve("github/kasuminova/ssoptimizer/common/font/layout-tables")
        directory.mkdirs()
        val expected = fontTables.files.map { it.nameWithoutExtension + ".metrics" }.toSet()
        directory.listFiles { file -> file.isFile && file.extension == "metrics" && file.name !in expected }
            ?.forEach { check(it.delete()) { "无法删除已撤销的字体度量资源：$it" } }
        val numericField = Regex("(\\w+)=\\s*(-?\\d+)")
        for (table in fontTables) {
            DataOutputStream(directory.resolve(table.nameWithoutExtension + ".metrics").outputStream().buffered()).use { out ->
                table.forEachLine(Charsets.ISO_8859_1) { line ->
                    val (tag, fields) = when (line.substringBefore(' ')) {
                        "info" -> 1 to listOf("size", "aa")
                        "common" -> 2 to listOf("lineHeight", "base")
                        "char" -> 3 to listOf("id", "width", "height", "xoffset", "yoffset", "xadvance")
                        "kerning" -> 4 to listOf("first", "second", "amount")
                        else -> return@forEachLine
                    }
                    val values = numericField.findAll(line).associate { it.groupValues[1] to it.groupValues[2].toInt() }
                    out.writeByte(tag)
                    for (field in fields) out.writeInt(values.getValue(field))
                }
                out.writeByte(0)
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
