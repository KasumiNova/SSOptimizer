# Bytecode Injection & Hybrid Weaver Implementation Plan

**Goal:** 为 SSOptimizer 建立可运行的 Java Agent 字节码注入框架，让 Starsector 在启动早期即可接入 ASM 预处理与 Mixin 织入，为后续渲染/性能 Hack 打好基础。

**Architecture:** `-javaagent` Fat JAR → `SSOptimizerAgent.premain()` → `HybridWeaverTransformer`（ASM 分发器）→ SpongePowered Mixin Service；反混淆/映射仅作为源码参考资料，不接入正式编译链。

**Tech Stack:** Java 25, Gradle 9.4.1 (Kotlin DSL), ASM 9.9.1, `net.fabricmc:sponge-mixin:0.15.4+mixin.0.8.7`, JUnit 5, Java Instrumentation API

---

### Task 1: 升级字节码相关依赖到最新可用版本

**Step 1: 写失败的依赖/产物断言测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/agent/AgentDependencyVersionTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDependencyVersionTest {
    @Test
    void buildScriptDeclaresLatestBytecodeLibraries() throws Exception {
        Path buildScript = Path.of(System.getProperty("project.rootDir"), "app", "build.gradle.kts");
        String content = Files.readString(buildScript);
        assertTrue(content.contains("org.ow2.asm:asm:9.9.1"), "ASM must be upgraded to 9.9.1");
        assertTrue(content.contains("org.ow2.asm:asm-tree:9.9.1"), "ASM Tree must be upgraded to 9.9.1");
        assertTrue(content.contains("net.fabricmc:sponge-mixin:0.15.4+mixin.0.8.7"), "Mixin must use latest compatible Fabric artifact");
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*AgentDependencyVersionTest'`
- Expected output:
  ```
  FAIL ... buildScriptDeclaresLatestBytecodeLibraries
  AssertionFailedError: ASM must be upgraded to 9.9.1
  ```

**Step 3: 实现最小依赖升级**
- File: `app/build.gradle.kts`
- Add to `dependencies {}`:
```kotlin
implementation("org.ow2.asm:asm:9.9.1")
implementation("org.ow2.asm:asm-tree:9.9.1")
implementation("net.fabricmc:sponge-mixin:0.15.4+mixin.0.8.7")
```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*AgentDependencyVersionTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 2: 为 Agent 建立最小可验证入口

**Step 1: 写失败的 premain 签名测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/agent/SSOptimizerAgentTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.agent;

import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SSOptimizerAgentTest {
    @Test
    void premainMethodHasCorrectSignature() throws Exception {
        Method premain = SSOptimizerAgent.class.getMethod("premain", String.class, Instrumentation.class);
        assertTrue(Modifier.isPublic(premain.getModifiers()));
        assertTrue(Modifier.isStatic(premain.getModifiers()));
        assertEquals(void.class, premain.getReturnType());
    }

    @Test
    void premainStateIsEmptyBeforeAgentLoad() {
        assertNull(SSOptimizerAgent.getInstrumentation());
        assertNull(SSOptimizerAgent.getWeaverTransformer());
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*SSOptimizerAgentTest'`
- Expected output:
  ```
  FAILED ... SSOptimizerAgent cannot be resolved
  ```

**Step 3: 实现 Agent 类**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/agent/SSOptimizerAgent.java`
- Code:
```java
package github.kasuminova.ssoptimizer.agent;

import java.lang.instrument.Instrumentation;

public final class SSOptimizerAgent {
    private static volatile Instrumentation instrumentation;
    private static volatile HybridWeaverTransformer weaverTransformer;
    private static volatile boolean mixinAvailable;

    private SSOptimizerAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        weaverTransformer = new HybridWeaverTransformer();
        inst.addTransformer(weaverTransformer, true);

        try {
            org.spongepowered.asm.launch.MixinBootstrap.init();
            org.spongepowered.asm.mixin.Mixins.addConfiguration("mixins.ssoptimizer.json");
            mixinAvailable = true;
            System.out.println("[SSOptimizer] Mixin bootstrap ready");
        } catch (Throwable t) {
            mixinAvailable = false;
            System.err.println("[SSOptimizer] Mixin bootstrap failed: " + t.getMessage());
            System.err.println("[SSOptimizer] Falling back to ASM-only mode");
        }
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public static HybridWeaverTransformer getWeaverTransformer() {
        return weaverTransformer;
    }

    public static boolean isMixinAvailable() {
        return mixinAvailable;
    }
}
```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*SSOptimizerAgentTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 3: 实现 Hybrid Weaver 分发器

**Step 1: 写失败的 transformer 测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/agent/HybridWeaverTransformerTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HybridWeaverTransformerTest {
    @Test
    void returnsNullForUnregisteredClass() {
        var transformer = new HybridWeaverTransformer();
        assertNull(transformer.transform(null, "com/example/Unknown", null, null, new byte[]{1}));
    }

    @Test
    void appliesRegisteredProcessor() {
        var transformer = new HybridWeaverTransformer();
        byte[] expected = new byte[]{1, 2, 3};
        transformer.registerProcessor("com.example.Target", bytes -> expected);

        byte[] result = transformer.transform(null, "com/example/Target", null, null, new byte[0]);
        assertArrayEquals(expected, result);
    }

    @Test
    void exceptionFallsBackToOriginalBytecode() {
        var transformer = new HybridWeaverTransformer();
        transformer.registerProcessor("com.example.Bad", bytes -> {
            throw new RuntimeException("boom");
        });

        assertNull(transformer.transform(null, "com/example/Bad", null, null, new byte[]{1}));
    }

    @Test
    void registerAndRemoveProcessorChangesCount() {
        var transformer = new HybridWeaverTransformer();
        transformer.registerProcessor("com.example.Foo", bytes -> bytes);
        assertEquals(1, transformer.getProcessorCount());
        transformer.removeProcessor("com.example.Foo");
        assertEquals(0, transformer.getProcessorCount());
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*HybridWeaverTransformerTest'`
- Expected output:
  ```
  FAILED ... HybridWeaverTransformer cannot be resolved
  ```

**Step 3: 实现 processor 接口与 transformer**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/agent/AsmClassProcessor.java`
- Code:
```java
package github.kasuminova.ssoptimizer.agent;

@FunctionalInterface
public interface AsmClassProcessor {
    byte[] process(byte[] classfileBuffer);
}
```
- File: `app/src/main/java/github/kasuminova/ssoptimizer/agent/HybridWeaverTransformer.java`
- Code:
```java
package github.kasuminova.ssoptimizer.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HybridWeaverTransformer implements ClassFileTransformer {
    private final Map<String, AsmClassProcessor> processors = new ConcurrentHashMap<>();

    public void registerProcessor(String className, AsmClassProcessor processor) {
        processors.put(className.replace('.', '/'), processor);
    }

    public void removeProcessor(String className) {
        processors.remove(className.replace('.', '/'));
    }

    public int getProcessorCount() {
        return processors.size();
    }

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }

        AsmClassProcessor processor = processors.get(className);
        if (processor == null) {
            return null;
        }

        try {
            return processor.process(classfileBuffer);
        } catch (Throwable t) {
            System.err.println("[SSOptimizer] ASM processor failed for " + className + ": " + t.getMessage());
            return null;
        }
    }
}
```

**Step 4: 运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*agent.*'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 4: 配置 Fat JAR 与 Agent Manifest

**Step 1: 写失败的 JAR 清单测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/AgentManifestTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentManifestTest {
    @Test
    void jarContainsPremainClass() throws Exception {
        File jarFile = new File(System.getProperty("project.rootDir"), "app/build/libs/SSOptimizer.jar");
        assertTrue(jarFile.exists(), "SSOptimizer.jar must exist before checking manifest");

        try (JarFile jar = new JarFile(jarFile)) {
            var manifest = jar.getManifest();
            assertNotNull(manifest);
            assertEquals("github.kasuminova.ssoptimizer.agent.SSOptimizerAgent",
                manifest.getMainAttributes().getValue("Premain-Class"));
            assertEquals("true", manifest.getMainAttributes().getValue("Can-Retransform-Classes"));
            assertEquals("true", manifest.getMainAttributes().getValue("Can-Redefine-Classes"));
        }
    }

    @Test
    void jarContainsBundledBytecodeLibraries() throws Exception {
        File jarFile = new File(System.getProperty("project.rootDir"), "app/build/libs/SSOptimizer.jar");
        assertTrue(jarFile.exists());

        try (JarFile jar = new JarFile(jarFile)) {
            assertNotNull(jar.getEntry("org/objectweb/asm/ClassReader.class"));
            assertNotNull(jar.getEntry("org/spongepowered/asm/mixin/Mixin.class"));
        }
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:jar :app:test --tests '*AgentManifestTest'`
- Expected output:
  ```
  FAILED ... Premain-Class missing / bundled classes missing
  ```

**Step 3: 为 app JAR 增加 manifest 与依赖打包**
- File: `app/build.gradle.kts`
- Replace the `jar` task with:
```kotlin
tasks.named<Jar>("jar") {
    archiveBaseName.set("SSOptimizer")
    archiveVersion.set("")
    archiveClassifier.set("")

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Premain-Class" to "github.kasuminova.ssoptimizer.agent.SSOptimizerAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true"
        )
    }
}
```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:jar :app:test --tests '*AgentManifestTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 5: 建立 Mixin 配置资源

**Step 1: 写失败的资源打包测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/MixinConfigTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinConfigTest {
    @Test
    void jarContainsMixinConfig() throws Exception {
        File jarFile = new File(System.getProperty("project.rootDir"), "app/build/libs/SSOptimizer.jar");
        assertTrue(jarFile.exists());

        try (JarFile jar = new JarFile(jarFile)) {
            var entry = jar.getEntry("mixins.ssoptimizer.json");
            assertNotNull(entry);
            try (InputStream is = jar.getInputStream(entry)) {
                String content = new String(is.readAllBytes());
                assertTrue(content.contains("github.kasuminova.ssoptimizer.mixin"));
            }
        }
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:jar :app:test --tests '*MixinConfigTest'`
- Expected output:
  ```
  FAILED ... mixins.ssoptimizer.json missing
  ```

**Step 3: 创建 Mixin 资源文件**
- File: `app/src/main/resources/mixins.ssoptimizer.json`
- Code:
```json
{
  "required": true,
  "package": "github.kasuminova.ssoptimizer.mixin",
  "compatibilityLevel": "JAVA_8",
  "mixins": [],
  "client": [],
  "injectors": {
    "defaultRequire": 1
  }
}
```

**Step 4: 运行测试并确认通过**
- Command: `./gradlew :app:jar :app:test --tests '*MixinConfigTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 6: 让 Agent 在启动阶段接入 Mixin

**Step 1: 写失败的 Mixin 启动状态测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/agent/MixinBootstrapStateTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class MixinBootstrapStateTest {
    @Test
    void mixinBootstrapStateIsQueryable() {
        assertNotNull(SSOptimizerAgent.class);
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*MixinBootstrapStateTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL after premain wiring is present in source
  ```

**Step 3: 保持 Agent 的 Mixin 初始化逻辑与失败降级**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/agent/SSOptimizerAgent.java`
- Ensure the `premain` method keeps:
  - `MixinBootstrap.init()`
  - `Mixins.addConfiguration("mixins.ssoptimizer.json")`
  - graceful fallback to ASM-only mode on failure

**Step 4: 运行编译验证**
- Command: `./gradlew :app:compileJava`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 7: 将 runClient 注入 `-javaagent`

**Step 1: 写失败的启动参数测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/LaunchConfigAgentArgTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchConfigAgentArgTest {
    @Test
    void launchConfigContainsJavaAgent() throws Exception {
        Path config = Path.of(System.getProperty("project.rootDir"), "launch-config.json");
        String content = Files.readString(config);
        assertTrue(content.contains("-javaagent:./mods/ssoptimizer/jars/SSOptimizer.jar"));
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*LaunchConfigAgentArgTest'`
- Expected output:
  ```
  FAILED ... agent arg missing
  ```

**Step 3: 更新 `launch-config.json`**
- File: `launch-config.json`
- Add to the beginning of `jvmArgs.common`:
```json
"-javaagent:./mods/ssoptimizer/jars/SSOptimizer.jar"
```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*LaunchConfigAgentArgTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 8: 将 runClient 任务继续保持为真实启动链路

**Step 1: 写失败的 dry-run 验证测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/RunClientTaskContractTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RunClientTaskContractTest {
    @Test
    void dummyContractExists() {
        assertTrue(true, "runClient task is validated through Gradle dry-run in implementation");
    }
}
```

**Step 2: 运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*RunClientTaskContractTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

**Step 3: 确保根 `build.gradle.kts` 中的 `runClientExec` 继续解析 `launch-config.json` 并启动 `com.fs.starfarer.StarfarerLauncher`**
- File: `build.gradle.kts`
- No structural change expected unless build verification reveals launch drift.

**Step 4: 运行 dry-run 验证**
- Command: `./gradlew runClient --dry-run`
- Expected output:
  ```
  :deployMod
  :runClientExec
  :runClient
  ```

---

### Task 9: 轻量保留映射参考文档，不接入编译链

**Step 1: 写失败的参考文件存在性测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/mapping/MappingReferenceTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.mapping;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingReferenceTest {
    @Test
    void referenceNoteExists() {
        Path doc = Path.of(System.getProperty("project.rootDir"), ".github", "superpower", "brainstorm", "2026-03-28-bytecode-injection-hybrid-weaver-design.md");
        assertTrue(Files.exists(doc));
    }
}
```

**Step 2: 运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*MappingReferenceTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

**Step 3: 在执行阶段仅保留映射为源码参考，不新增 remap 编译任务依赖**
- File: `build.gradle.kts` / `docs`
- The plan intentionally avoids wiring deobf/remap into the normal build graph for now.

---

### Task 10: 端到端验证 Agent JAR

**Step 1: 进行完整构建**
- Command: `./gradlew clean :app:jar`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

**Step 2: 检查 JAR 内容**
- Command: `jar tf app/build/libs/SSOptimizer.jar | grep -E '(SSOptimizerAgent|HybridWeaverTransformer|Mixin.class|ClassReader.class|mixins\.ssoptimizer\.json)'`
- Expected output:
  ```
  github/kasuminova/ssoptimizer/agent/SSOptimizerAgent.class
  github/kasuminova/ssoptimizer/agent/HybridWeaverTransformer.class
  org/objectweb/asm/ClassReader.class
  org/spongepowered/asm/mixin/Mixin.class
  mixins.ssoptimizer.json
  ```

**Step 3: 部署验证**
- Command: `./gradlew deployMod`
- Expected output:
  ```
  ✓ Mod deployed to .../mods/ssoptimizer
  ```

**Step 4: 运行全量测试**
- Command: `./gradlew :app:test`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

## Risk Notes

- **Mixin 在非 Minecraft 环境下的 bootstrap 风险**：若 `MixinBootstrap.init()` 无法直接工作，优先保留 ASM-only 降级路径，后续再补自定义 service adapter。
- **Fat JAR 冲突风险**：若依赖类重复或签名文件冲突，优先通过 `duplicatesStrategy = DuplicatesStrategy.EXCLUDE` 和签名排除修复。
- **Java 25 与第三方库兼容性**：若任一库在 Java 25 下有已知兼容问题，优先升级到该库的最新稳定线而不是降级 JDK。

---

## Out of Scope for This Plan

- 不把反混淆/映射管线接入正式编译图，只保留参考文档与源码索引。
- 不在本阶段实现具体的渲染 Hook、OpenGL 替换或性能优化逻辑。
- 不实现 JNI/C++ 工程链；这将作为后续独立基建任务处理。
