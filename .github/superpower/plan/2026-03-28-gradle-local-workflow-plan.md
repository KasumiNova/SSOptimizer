# SSOptimizer Gradle 本地工作流 — 实现计划

**Goal:** 基于已定稿设计，将 SSOptimizer 从当前"骨架 + Python docsCheck"升级为"Minecraft 风格本地工作流 + JUnit 文档契约 + deobf/reobf 管线骨架 + 强阻断质量门"。

**Architecture:** 单根任务编排（root `build.gradle.kts` 定义入口），`app` 模块提供 `docsTest` source set，mapping 管线先占位后迭代。

**Tech Stack:** Gradle 9.4.1、Kotlin DSL、Java 25、JUnit 5。

---

## Task 1: JUnit docsTest source set

### Step 1: 在 `app/build.gradle.kts` 声明 `docsTest` source set 与 task

- File: `app/build.gradle.kts`
- Code（追加到现有文件末尾）:
```kotlin
// --- docsTest source set ---
sourceSets {
    create("docsTest") {
        java.srcDir("src/docsTest/java")
    }
}

val docsTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val docsTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

tasks.register<Test>("docsTest") {
    group = "verification"
    description = "Run documentation contract tests"
    testClassesDirs = sourceSets["docsTest"].output.classesDirs
    classpath = sourceSets["docsTest"].runtimeClasspath
    useJUnitPlatform()
    // 传递项目根目录给测试
    systemProperty("project.rootDir", rootProject.rootDir.absolutePath)
}
```

### Step 2: 创建第一个失败测试 — 章节完整性

- File: `app/src/docsTest/java/github/kasuminova/ssoptimizer/docs/BaselineDocSectionsTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.docs;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BaselineDocSectionsTest {

    static final Path ROOT = Path.of(System.getProperty("project.rootDir"));

    static final Path BASELINE = ROOT.resolve(
            "docs/design/dev-environment-baseline-implementation.md");

    static final List<String> REQUIRED_SECTIONS = List.of(
            "# SSOptimizer 开发环境基线实现文档",
            "## 1. 目标与范围",
            "## 2. Minecraft 风格环境标准对齐",
            "## 3. 工具链与版本基线",
            "## 4. 运行配置（Run Profiles）",
            "## 5. IDE 与调试体验基线",
            "## 6. 混淆映射（Mapping）工作流",
            "## 7. Mixin/ASM 注入开发规范",
            "## 8. 测试与质量门",
            "## 9. 故障排查与回滚",
            "## 10. 团队协作规范"
    );

    @Test
    void baselineDocContainsAllRequiredSections() throws IOException {
        assertTrue(Files.exists(BASELINE),
                "Missing: docs/design/dev-environment-baseline-implementation.md");
        String content = Files.readString(BASELINE);
        for (String section : REQUIRED_SECTIONS) {
            assertTrue(content.contains(section),
                    "Missing section: " + section);
        }
    }
}
```

### Step 3: 运行测试并确认通过（文档已存在）

- Command: `./gradlew :app:docsTest`
- Expected output:
```
BUILD SUCCESSFUL
```

---

## Task 2: README 链接契约测试（JUnit）

### Step 1: 创建 README 链接测试

- File: `app/src/docsTest/java/github/kasuminova/ssoptimizer/docs/ReadmeLinksTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.docs;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReadmeLinksTest {

    static final Path ROOT = Path.of(System.getProperty("project.rootDir"));
    static final Path README = ROOT.resolve("README.md");

    static final List<String> REQUIRED_LINKS = List.of(
            "docs/design/dev-environment-baseline-implementation.md",
            "docs/design/dev-environment-onboarding-checklist.md",
            "docs/design/dev-environment-mapping-workflow.md",
            "docs/design/dev-environment-run-profiles.md",
            "docs/design/dev-environment-troubleshooting.md"
    );

    @Test
    void readmeContainsAllDocLinks() throws IOException {
        assertTrue(Files.exists(README), "README.md must exist");
        String content = Files.readString(README);
        for (String link : REQUIRED_LINKS) {
            assertTrue(content.contains(link),
                    "README missing link: " + link);
        }
    }
}
```

### Step 2: 运行测试并确认通过

- Command: `./gradlew :app:docsTest`
- Expected output: `BUILD SUCCESSFUL`

---

## Task 3: Mapping 关键字契约测试（JUnit）

### Step 1: 创建 Mapping 工作流契约测试

- File: `app/src/docsTest/java/github/kasuminova/ssoptimizer/docs/MappingWorkflowDocTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.docs;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MappingWorkflowDocTest {

    static final Path ROOT = Path.of(System.getProperty("project.rootDir"));

    static final Path MAPPING_DOC = ROOT.resolve(
            "docs/design/dev-environment-mapping-workflow.md");

    static final List<String> REQUIRED_SECTIONS = List.of(
            "# SSOptimizer 渲染链路 Mapping 工作流",
            "## 命名规范",
            "## 证据链规范",
            "## 版本化与审查"
    );

    @Test
    void mappingDocContainsAllRequiredSections() throws IOException {
        assertTrue(Files.exists(MAPPING_DOC),
                "Missing: docs/design/dev-environment-mapping-workflow.md");
        String content = Files.readString(MAPPING_DOC);
        for (String section : REQUIRED_SECTIONS) {
            assertTrue(content.contains(section),
                    "Missing section: " + section);
        }
    }
}
```

### Step 2: 运行测试并确认通过

- Command: `./gradlew :app:docsTest`
- Expected output: `BUILD SUCCESSFUL`

---

## Task 4: 重构根 `docsCheck` 改为聚合 JUnit（替换 Python）

### Step 1: 替换 `build.gradle.kts` 中的 `docsCheck` 任务

- File: `build.gradle.kts`
- 将现有 `docsCheck`（Python 调用）替换为:
```kotlin
tasks.register("docsCheck") {
    group = "verification"
    description = "Validate development-environment baseline docs contract (JUnit)"
    dependsOn(":app:docsTest")
}
```

### Step 2: 运行验证

- Command: `./gradlew docsCheck`
- Expected output: `BUILD SUCCESSFUL`

### Step 3: 确认旧 Python 测试仍可单独运行（不删除，仅解耦）

- Command: `python3 tools/docs/test_dev_environment_baseline.py`
- Expected output: `OK`（兼容保留，无硬依赖）

---

## Task 5: 根任务骨架 — 入口编排

### Step 1: 在根 `build.gradle.kts` 添加 MC 风格入口任务

- File: `build.gradle.kts`
- Code（追加到文件末尾）:
```kotlin
// --- Minecraft-style local workflow tasks ---

tasks.register("bootstrapDev") {
    group = "dev workflow"
    description = "Initialize and verify development environment"
    dependsOn("doctor", "docsCheck")
    doLast {
        println("✓ Development environment bootstrap complete")
    }
}

tasks.register("qualityGateLocal") {
    group = "dev workflow"
    description = "Run all quality gates (docs + tests + diagnostics)"
    dependsOn("docsCheck", ":app:test", "doctor")
    doLast {
        println("✓ All local quality gates passed")
    }
}

tasks.register("devCycle") {
    group = "dev workflow"
    description = "Daily development cycle: quality gates + build"
    dependsOn("qualityGateLocal", ":app:classes")
    doLast {
        println("✓ Dev cycle complete — ready to run")
    }
}

tasks.register("releasePrepLocal") {
    group = "dev workflow"
    description = "Pre-release local validation (quality gates + reobf verify)"
    dependsOn("qualityGateLocal")
    // Task 8 将接线: dependsOn("verifyReobf")
    doLast {
        println("✓ Local release preparation complete")
    }
}
```

### Step 2: 运行 devCycle 验证全链路

- Command: `./gradlew devCycle`
- Expected output:
```
> Task :doctor
> Task :docsCheck
> Task :app:test
> Task :app:classes
> Task :devCycle
✓ Dev cycle complete — ready to run
BUILD SUCCESSFUL
```

---

## Task 6: Run Profile 任务骨架

### Step 1: 在根 `build.gradle.kts` 添加运行档任务

- File: `build.gradle.kts`
- Code（追加）:
```kotlin
tasks.register("runClient") {
    group = "dev workflow"
    description = "Run game in deobf dev mode (default profile)"
    dependsOn(":app:classes")
    doLast {
        // Phase 2: 实际启动逻辑（JavaExec + named-dev classpath）
        println("[runClient] Dev profile — not yet wired to game launch")
        println("  Next: implement prepareDeobfWorkspace + launch config")
    }
}

tasks.register("runSafe") {
    group = "dev workflow"
    description = "Run game with all injections disabled (safe profile)"
    dependsOn(":app:classes")
    doLast {
        println("[runSafe] Safe profile — not yet wired to game launch")
    }
}

tasks.register("runTrace") {
    group = "dev workflow"
    description = "Run game with verbose tracing enabled (trace profile)"
    dependsOn(":app:classes")
    doLast {
        println("[runTrace] Trace profile — not yet wired to game launch")
    }
}
```

### Step 2: 验证任务注册

- Command: `./gradlew tasks --group "dev workflow"`
- Expected output: 列出 `bootstrapDev`、`qualityGateLocal`、`devCycle`、`releasePrepLocal`、`runClient`、`runSafe`、`runTrace`

---

## Task 7: Mapping 管线占位任务

### Step 1: 在根 `build.gradle.kts` 添加 mapping 占位

- File: `build.gradle.kts`
- Code（追加）:
```kotlin
// --- Mapping pipeline (Phase 2 stubs) ---

tasks.register("prepareDeobfWorkspace") {
    group = "mapping"
    description = "Prepare deobfuscated workspace (download mappings + remap)"
    doLast {
        println("[prepareDeobfWorkspace] Stub — will download/verify mappings and produce named-dev jar")
    }
}

tasks.register("remapToNamed") {
    group = "mapping"
    description = "Remap obfuscated classes to named (development) namespace"
    dependsOn("prepareDeobfWorkspace")
    doLast {
        println("[remapToNamed] Stub — will remap obf -> named")
    }
}

tasks.register("assembleReobf") {
    group = "mapping"
    description = "Remap build artifact back to obfuscated namespace for release"
    dependsOn(":app:jar")
    doLast {
        println("[assembleReobf] Stub — will remap named -> obf")
    }
}

tasks.register("verifyReobf") {
    group = "mapping"
    description = "Verify reobfuscated artifact integrity (signatures, targets)"
    dependsOn("assembleReobf")
    doLast {
        println("[verifyReobf] Stub — will check method signatures, mixin targets, entry points")
    }
}
```

### Step 2: 验证 mapping 任务链

- Command: `./gradlew verifyReobf`
- Expected output:
```
> Task :prepareDeobfWorkspace
> Task :app:jar
> Task :assembleReobf
> Task :verifyReobf
BUILD SUCCESSFUL
```

---

## Task 8: 接线 releasePrepLocal → verifyReobf

### Step 1: 更新 `releasePrepLocal` 使其依赖 `verifyReobf`

- File: `build.gradle.kts`
- 在 `releasePrepLocal` 的 `dependsOn` 中追加 `"verifyReobf"`:
```kotlin
tasks.register("releasePrepLocal") {
    group = "dev workflow"
    description = "Pre-release local validation (quality gates + reobf verify)"
    dependsOn("qualityGateLocal", "verifyReobf")
    doLast {
        println("✓ Local release preparation complete")
    }
}
```

### Step 2: 验证完整 release 链

- Command: `./gradlew releasePrepLocal`
- Expected output:
```
> Task :doctor
> Task :docsCheck
> Task :app:test
> Task :qualityGateLocal
> Task :prepareDeobfWorkspace
> Task :app:jar
> Task :assembleReobf
> Task :verifyReobf
> Task :releasePrepLocal
✓ Local release preparation complete
BUILD SUCCESSFUL
```

---

## Task 9: Minecraft 风格关键字更新到主文档

### Step 1: 更新 `dev-environment-baseline-implementation.md` 的命令段

- File: `docs/design/dev-environment-baseline-implementation.md`
- 在 `## 3. 工具链与版本基线` 的"标准验证命令"段追加:
```markdown
- `./gradlew bootstrapDev`
- `./gradlew devCycle`
- `./gradlew releasePrepLocal`
- `./gradlew runClient`
```

### Step 2: 运行 docsCheck 确认通过

- Command: `./gradlew docsCheck`
- Expected output: `BUILD SUCCESSFUL`

---

## Task 10: 最终收口验证

### Step 1: 文档契约全量

- Command: `./gradlew docsCheck`
- Expected output: `BUILD SUCCESSFUL`

### Step 2: 完整 devCycle

- Command: `./gradlew devCycle`
- Expected output: `BUILD SUCCESSFUL`

### Step 3: 完整 releasePrepLocal

- Command: `./gradlew releasePrepLocal`
- Expected output: `BUILD SUCCESSFUL`

### Step 4: 任务发现性

- Command: `./gradlew tasks --group "dev workflow"`
- Expected output: 全部 7 个 dev workflow 任务可见

---

## 验证清单

- [ ] `docsTest` source set 可编译运行（JUnit5）
- [ ] 3 个文档契约测试类全部通过
- [ ] `docsCheck` 不再依赖 Python
- [ ] `devCycle` 单命令跑通（质量门 + 编译）
- [ ] `releasePrepLocal` 包含 `verifyReobf` 链
- [ ] mapping 占位任务已注册（4 个）
- [ ] Run profile 占位任务已注册（3 个）
- [ ] `tasks --group "dev workflow"` 展示完整

---

## 交接说明

本计划产出后，交给 `superpower-execute` 执行。
执行阶段需严格按 Task 1→10 顺序推进，每步验证后才进入下一步。
Mapping 占位与 Run Profile 占位在本轮仅注册骨架，实际逻辑留待 Phase 2（渲染优化开发启动后）填充。
