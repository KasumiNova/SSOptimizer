# SSOptimizer Real Dev Environment Implementation Plan

**Goal:** 让 SSOptimizer 从“工作流骨架”升级为“可真实编译、可部署、可启动”的 Starsector 开发环境，做到最基本的开发链路跑通。

**Architecture:**
- `gradle.properties` 提供 `starsector.gameDir` 作为唯一游戏根目录来源
- `app/build.gradle.kts` 通过 `compileOnly` / `testImplementation` 引入 Starsector 根目录 JAR
- `mod_info.json` 定义模组元数据与主插件入口
- 根 `build.gradle.kts` 提供 `deployMod`、`runClient` 等真实开发任务
- `launch-config.json` 统一管理启动参数与 classpath
- 先跑通“编译 → 打包 → 部署 → 启动”的最小闭环，再逐步补全更高级的映射与调试能力

**Tech Stack:** Java 25, Gradle 9.4.1 (Kotlin DSL), Starsector 0.98a-RC8, JUnit 5, JavaExec/Copy 任务

---

### Task 1: 配置 Starsector 游戏目录属性

**Step 1: 写入游戏目录属性**
- File: `gradle.properties`
- Code:
  ```properties
  # Starsector game directory
  starsector.gameDir=/mnt/windows_data/Games/Starsector098-linux
  ```

**Step 2: 验证属性可读**
- Command: `./gradlew properties | grep starsector.gameDir`
- Expected output:
  ```
  starsector.gameDir: /mnt/windows_data/Games/Starsector098-linux
  ```

---

### Task 2: 让 app 模块能够编译 Starsector API

**Step 1: 写一个会失败的 API 编译测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/StarsectorApiSmokeTest.java`
- Code:
  ```java
  package github.kasuminova.ssoptimizer;

  import org.junit.jupiter.api.Test;

  import static org.junit.jupiter.api.Assertions.assertNotNull;

  class StarsectorApiSmokeTest {
      @Test
      void canResolveApiClass() throws Exception {
          Class<?> cls = Class.forName("com.fs.starfarer.api.Global");
          assertNotNull(cls);
      }
  }
  ```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*StarsectorApiSmokeTest'`
- Expected output:
  ```
  FAIL ...
  cannot find symbol: class Global
  ```

**Step 3: 为 app 模块添加 Starsector JAR 依赖**
- File: `app/build.gradle.kts`
- Code to add inside `dependencies {}`:
  ```kotlin
  val gameDirPath = providers.gradleProperty("starsector.gameDir").orNull
  if (gameDirPath != null) {
      val gameDir = file(gameDirPath)
      compileOnly(fileTree(gameDir) { include("*.jar") })
      testImplementation(fileTree(gameDir) { include("*.jar") })
  }
  ```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*StarsectorApiSmokeTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 3: 创建最小可用的模组入口类

**Step 1: 写一个会失败的插件测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/SSOptimizerModPluginTest.java`
- Code:
  ```java
  package github.kasuminova.ssoptimizer;

  import com.fs.starfarer.api.BaseModPlugin;
  import org.junit.jupiter.api.Test;

  import static org.junit.jupiter.api.Assertions.assertInstanceOf;

  class SSOptimizerModPluginTest {
      @Test
      void modPluginExtendsBaseModPlugin() {
          SSOptimizerModPlugin plugin = new SSOptimizerModPlugin();
          assertInstanceOf(BaseModPlugin.class, plugin);
      }
  }
  ```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*SSOptimizerModPluginTest'`
- Expected output:
  ```
  cannot find symbol: class SSOptimizerModPlugin
  ```

**Step 3: 实现插件类**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/SSOptimizerModPlugin.java`
- Code:
  ```java
  package github.kasuminova.ssoptimizer;

  import com.fs.starfarer.api.BaseModPlugin;

  public class SSOptimizerModPlugin extends BaseModPlugin {
      @Override
      public void onApplicationLoad() throws Exception {
          System.out.println("[SSOptimizer] Loaded on Java " + Runtime.version());
      }
  }
  ```

**Step 4: 运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*SSOptimizerModPluginTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 4: 创建 `mod_info.json`

**Step 1: 写一个会失败的 mod 元数据测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/ModInfoJsonTest.java`
- Code:
  ```java
  package github.kasuminova.ssoptimizer;

  import org.junit.jupiter.api.Test;

  import java.nio.file.Files;
  import java.nio.file.Path;

  import static org.junit.jupiter.api.Assertions.assertAll;
  import static org.junit.jupiter.api.Assertions.assertTrue;

  class ModInfoJsonTest {
      @Test
      void modInfoJsonExists() {
          Path modInfo = Path.of(System.getProperty("project.rootDir"), "mod_info.json");
          assertTrue(Files.exists(modInfo), "mod_info.json must exist at project root");
      }

      @Test
      void modInfoJsonContainsRequiredFields() throws Exception {
          Path modInfo = Path.of(System.getProperty("project.rootDir"), "mod_info.json");
          String content = Files.readString(modInfo);
          assertAll(
              () -> assertTrue(content.contains("\"id\""), "must have id field"),
              () -> assertTrue(content.contains("\"name\""), "must have name field"),
              () -> assertTrue(content.contains("\"version\""), "must have version field"),
              () -> assertTrue(content.contains("\"gameVersion\""), "must have gameVersion field"),
              () -> assertTrue(content.contains("\"jars\""), "must have jars field"),
              () -> assertTrue(content.contains("\"modPlugin\""), "must have modPlugin field")
          );
      }
  }
  ```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*ModInfoJsonTest'`
- Expected output:
  ```
  mod_info.json must exist at project root
  ```

**Step 3: 创建 mod 元数据文件**
- File: `mod_info.json`
- Code:
  ```json
  {
    "id": "ssoptimizer",
    "name": "SSOptimizer",
    "author": "Hikari_Nova",
    "version": "0.1.0",
    "description": "Starsector rendering & performance optimizer",
    "gameVersion": "0.98a-RC8",
    "jars": ["jars/SSOptimizer.jar"],
    "modPlugin": "github.kasuminova.ssoptimizer.SSOptimizerModPlugin"
  }
  ```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*ModInfoJsonTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 5: 固定 JAR 输出名称

**Step 1: 为 JAR 产物写一个构建约束测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/JarOutputTest.java`
- Code:
  ```java
  package github.kasuminova.ssoptimizer;

  import org.junit.jupiter.api.Test;

  import java.nio.file.Files;
  import java.nio.file.Path;

  import static org.junit.jupiter.api.Assertions.assertTrue;

  class JarOutputTest {
      @Test
      void jarBuildDirectoryExists() {
          Path libsDir = Path.of(System.getProperty("project.rootDir"), "app", "build", "libs");
          assertTrue(Files.isDirectory(libsDir), "app/build/libs should exist after jar build");
      }
  }
  ```

**Step 2: 配置 jar 产物名**
- File: `app/build.gradle.kts`
- Code to add:
  ```kotlin
  tasks.named<Jar>("jar") {
      archiveBaseName.set("SSOptimizer")
      archiveVersion.set("")
      archiveClassifier.set("")
  }
  ```

**Step 3: 构建并检查产物**
- Command: `./gradlew :app:jar && ls -la app/build/libs/`
- Expected output:
  ```
  SSOptimizer.jar
  ```

---

### Task 6: 实现部署任务 `deployMod`

**Step 1: 将部署任务从概念变成实际 Copy 任务**
- File: `build.gradle.kts`
- Code:
  ```kotlin
  tasks.register<Copy>("deployMod") {
      group = "dev workflow"
      description = "Deploy mod to Starsector mods directory"
      dependsOn(":app:jar")

      val gameDirProvider = providers.gradleProperty("starsector.gameDir")
      val modId = "ssoptimizer"

      from(project(":app").layout.buildDirectory.dir("libs")) {
          into("jars")
      }
      from(rootProject.file("mod_info.json"))

      into(gameDirProvider.map { file(it).resolve("mods/$modId") })

      doLast {
          println("✓ Mod deployed to ${gameDirProvider.get()}/mods/$modId")
      }
  }
  ```

**Step 2: 执行部署并确认成功**
- Command: `./gradlew deployMod`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

**Step 3: 验证部署目录**
- Command: `ls -la /mnt/windows_data/Games/Starsector098-linux/mods/ssoptimizer/`
- Expected output:
  ```
  jars/
  mod_info.json
  ```

---

### Task 7: 创建开发启动配置 `launch-config.json`

**Step 1: 写入启动配置文件**
- File: `launch-config.json`
- Code:
  ```json
  {
    "jvmArgs": {
      "common": [
        "-Dfile.encoding=UTF-8",
        "-noverify",
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:+ShowCodeDetailsInExceptionMessages",
        "-XX:+TieredCompilation",
        "-XX:+DisableExplicitGC",
        "-XX:+AlwaysPreTouch",
        "-XX:+ParallelRefProcEnabled",
        "-XX:+UseZGC",
        "-XX:ReservedCodeCacheSize=256m",
        "-XX:CompilerDirectivesFile=./compiler_directives.txt",
        "-Djdk.xml.maxElementDepth=10000",
        "-XX:-BytecodeVerificationLocal",
        "-XX:-BytecodeVerificationRemote",
        "-Djava.util.Arrays.useLegacyMergeSort=true",
        "--enable-preview",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/java.nio.Buffer.UNSAFE=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.ref=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.Rectangle=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
        "-Xms4096m",
        "-Xmx4096m",
        "-Xss4m",
        "-Dcom.fs.starfarer.settings.paths.saves=./saves",
        "-Dcom.fs.starfarer.settings.paths.screenshots=./screenshots",
        "-Dcom.fs.starfarer.settings.paths.mods=./mods",
        "-Dcom.fs.starfarer.settings.paths.logs=."
      ],
      "linux": [
        "-Djava.library.path=./native/linux",
        "-Dcom.fs.starfarer.settings.linux=true"
      ]
    },
    "classpath": [
      "janino.jar",
      "commons-compiler.jar",
      "commons-compiler-jdk.jar",
      "starfarer.api.jar",
      "starfarer_obf.jar",
      "jogg-0.0.7.jar",
      "jorbis-0.0.15.jar",
      "json.jar",
      "lwjgl.jar",
      "jinput.jar",
      "log4j-1.2.9.jar",
      "lwjgl_util.jar",
      "fs.sound_obf.jar",
      "fs.common_obf.jar",
      "xstream-1.4.10.jar",
      "txw2-3.0.2.jar",
      "jaxb-api-2.4.0-b180830.0359.jar",
      "webp-imageio-0.1.6.jar"
    ]
  }
  ```

**Step 2: 校验 JSON**
- Command: `python3 -c "import json; json.load(open('launch-config.json'))"`
- Expected output:
  ```
  (no output)
  ```

---

### Task 8: 让 `runClient` 真正启动游戏

**Step 1: 将 stub `runClient` 改成 `JavaExec`**
- File: `build.gradle.kts`
- Code:
  ```kotlin
  tasks.register<JavaExec>("runClient") {
      group = "dev workflow"
      description = "Run game in dev mode (deploys mod first)"
      dependsOn("deployMod")

      doFirst {
          val gameDir = file(providers.gradleProperty("starsector.gameDir").get())
          workingDir = gameDir
          executable = gameDir.resolve("zulu25_linux/bin/java").absolutePath

          val config = groovy.json.JsonSlurper().parse(rootProject.file("launch-config.json")) as Map<*, *>
          val jvmArgsConfig = config["jvmArgs"] as Map<*, *>
          val commonArgs = jvmArgsConfig["common"] as List<String>
          val linuxArgs = jvmArgsConfig["linux"] as List<String>
          jvmArgs = commonArgs + linuxArgs

          val classpathJars = config["classpath"] as List<String>
          classpath = files(classpathJars.map { gameDir.resolve(it) })
          mainClass.set("com.fs.starfarer.StarfarerLauncher")
      }
  }
  ```

**Step 2: 运行 dry-run 验证任务链**
- Command: `./gradlew runClient --dry-run`
- Expected output:
  ```
  :app:jar
  :deployMod
  :runClient
  ```

---

### Task 9: 自动启用 mod 的最小辅助逻辑

**Step 1: 在部署后写入启用状态**
- File: `build.gradle.kts`
- Code to append inside `deployMod` `doLast`:
  ```kotlin
  val enabledModsFile = file(gameDirProvider.get()).resolve("mods/enabled_mods.json")
  if (enabledModsFile.exists()) {
      val content = enabledModsFile.readText()
      if (!content.contains("\"ssoptimizer\"")) {
          val updated = content.replace(
              Regex("(\"enabledMods\"\\s*:\\s*\\[)([^]]*)"),
              "$1$2,\n    \"ssoptimizer\""
          )
          enabledModsFile.writeText(updated)
          println("  → Added ssoptimizer to enabled_mods.json")
      }
  }
  ```

**Step 2: 再次执行部署并确认行为**
- Command: `./gradlew deployMod`
- Expected output:
  ```
  Added ssoptimizer to enabled_mods.json
  ```

---

### Task 10: 端到端验证编译链和启动链

**Step 1: 完整构建验证**
- Command: `./gradlew clean :app:jar`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

**Step 2: 部署验证**
- Command: `./gradlew deployMod`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

**Step 3: 任务发现性验证**
- Command: `./gradlew tasks --group "dev workflow"`
- Expected output:
  ```
  runClient
  deployMod
  qualityGateLocal
  devCycle
  releasePrepLocal
  ```

**Step 4: 启动链 dry-run 验证**
- Command: `./gradlew runClient --dry-run`
- Expected output:
  ```
  :app:jar
  :deployMod
  :runClient
  ```

---

## 验证清单

- [ ] `starsector.gameDir` 可读且指向正确安装目录
- [ ] `app` 模块能够编译并测试引用 `com.fs.starfarer.api.Global`
- [ ] `SSOptimizerModPlugin` 可编译并继承 `BaseModPlugin`
- [ ] `mod_info.json` 存在并包含必要字段
- [ ] 产物 JAR 命名为 `SSOptimizer.jar`
- [ ] `deployMod` 能把 JAR 和 `mod_info.json` 放入游戏 mods 目录
- [ ] `launch-config.json` 语法正确
- [ ] `runClient` 变成真实 `JavaExec` 启动链
- [ ] `enabled_mods.json` 可被最小化更新
- [ ] `clean :app:jar`、`deployMod`、`runClient --dry-run` 全部可通过
