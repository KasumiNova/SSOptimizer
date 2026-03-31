# Rendering Refactoring Implementation Plan (Phase A & B)

**Goal:** 实现一个基于 `ThreadLocal` 的渲染状态缓存，并对 Starsector 的高频渲染路径做 JNI 调用去重，优先覆盖纹理绑定、混合状态和 `glFinish` 之类的高代价调用。

**Architecture:** `Mixin` 负责高层注入点与字段/行为接入，`ASM` 负责对底层高频调用做精确擦除或短路；两者共用 `RenderStateCache` 作为状态镜像层。

**Tech Stack:** Java, Gradle Kotlin DSL, JUnit 5, Sponge Mixin, ASM, Java Instrumentation API

---

### Task 1: 建立渲染状态缓存核心 `RenderStateCache`

**Step 1: 写失败的状态缓存测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/RenderStateCacheTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderStateCacheTest {
    @AfterEach
    void tearDown() {
        RenderStateCache.clear();
    }

    @Test
    void tracksTextureBindingPerThread() {
        assertTrue(RenderStateCache.shouldBindTexture(101));
        assertFalse(RenderStateCache.shouldBindTexture(101));
        assertTrue(RenderStateCache.shouldBindTexture(102));
    }

    @Test
    void isolatesStateAcrossThreads() throws Exception {
        RenderStateCache.shouldBindTexture(200);

        final boolean[] result = new boolean[1];
        Thread t = new Thread(() -> result[0] = RenderStateCache.shouldBindTexture(200));
        t.start();
        t.join();

        assertTrue(result[0]);
    }

    @Test
    void resetsStateOnClear() {
        RenderStateCache.shouldBindTexture(300);
        RenderStateCache.clear();
        assertTrue(RenderStateCache.shouldBindTexture(300));
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*RenderStateCacheTest'`
- Expected output:
  ```
  FAILED ... RenderStateCache cannot be resolved
  ```

**Step 3: 实现最小可用缓存**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/RenderStateCache.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render;

public final class RenderStateCache {
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private RenderStateCache() {
    }

    public static boolean shouldBindTexture(int textureId) {
        State state = STATE.get();
        if (state.lastTextureId == textureId) {
            return false;
        }
        state.lastTextureId = textureId;
        return true;
    }

    public static void setLastTextureId(int textureId) {
        STATE.get().lastTextureId = textureId;
    }

    public static int getLastTextureId() {
        return STATE.get().lastTextureId;
    }

    public static void clear() {
        STATE.remove();
    }

    private static final class State {
        private int lastTextureId = -1;
    }
}
```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*RenderStateCacheTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 2: 建立渲染状态短路器 `RenderStateInterceptor`

**Step 1: 写失败的短路逻辑测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/RenderStateInterceptorTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderStateInterceptorTest {
    @AfterEach
    void tearDown() {
        RenderStateCache.clear();
    }

    @Test
    void shouldBindTextureOnlyOnceForSameId() {
        assertTrue(RenderStateInterceptor.shouldBindTexture(1));
        assertFalse(RenderStateInterceptor.shouldBindTexture(1));
        assertTrue(RenderStateInterceptor.shouldBindTexture(2));
    }

    @Test
    void invalidateStateRestoresFallbackPath() {
        assertTrue(RenderStateInterceptor.shouldBindTexture(5));
        RenderStateInterceptor.invalidateState();
        assertTrue(RenderStateInterceptor.shouldBindTexture(5));
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*RenderStateInterceptorTest'`
- Expected output:
  ```
  FAILED ... RenderStateInterceptor cannot be resolved
  ```

**Step 3: 实现最小短路逻辑**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/RenderStateInterceptor.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render;

public final class RenderStateInterceptor {
    private RenderStateInterceptor() {
    }

    public static boolean shouldBindTexture(int textureId) {
        return RenderStateCache.shouldBindTexture(textureId);
    }

    public static void invalidateState() {
        RenderStateCache.clear();
    }
}
```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*RenderStateInterceptorTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 3: 为 Phase A 注入 `CombatState.traverse` 旁路

**Step 1: 写失败的行为测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/CombatStateTraversalHookTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatStateTraversalHookTest {
    @Test
    void debugModeKeepsFinishCallsEnabled() {
        assertTrue(CombatStateTraversalHook.shouldCallFinish(true));
    }

    @Test
    void normalModeSkipsFinishCalls() {
        assertFalse(CombatStateTraversalHook.shouldCallFinish(false));
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*CombatStateTraversalHookTest'`
- Expected output:
  ```
  FAILED ... CombatStateTraversalHook cannot be resolved
  ```

**Step 3: 实现最小旁路判断**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/CombatStateTraversalHook.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render;

public final class CombatStateTraversalHook {
    private CombatStateTraversalHook() {
    }

    public static boolean shouldCallFinish(boolean debugMode) {
        return debugMode;
    }
}
```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*CombatStateTraversalHookTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 4: 为 Phase B 准备 Sprite / Particle 适配层

**Step 1: 写失败的适配器测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/SpriteRenderAdapterTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpriteRenderAdapterTest {
    @AfterEach
    void tearDown() {
        RenderStateCache.clear();
    }

    @Test
    void sameTextureCanShortCircuitRenderPath() {
        assertTrue(SpriteRenderAdapter.shouldRenderWithTexture(10));
        assertFalse(SpriteRenderAdapter.shouldRenderWithTexture(10));
    }

    @Test
    void differentTextureDoesNotShortCircuit() {
        assertTrue(SpriteRenderAdapter.shouldRenderWithTexture(11));
        assertTrue(SpriteRenderAdapter.shouldRenderWithTexture(12));
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*SpriteRenderAdapterTest'`
- Expected output:
  ```
  FAILED ... SpriteRenderAdapter cannot be resolved
  ```

**Step 3: 实现最小适配器**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/SpriteRenderAdapter.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render;

public final class SpriteRenderAdapter {
    private SpriteRenderAdapter() {
    }

    public static boolean shouldRenderWithTexture(int textureId) {
        return RenderStateCache.shouldBindTexture(textureId);
    }
}
```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*SpriteRenderAdapterTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 5: 连接 Mixin/ASM 注入点

**Step 1: 写失败的注入配置测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/RenderMixinConfigTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderMixinConfigTest {
    @Test
    void mixinConfigDeclaresRenderHooks() throws Exception {
        Path config = Path.of(System.getProperty("project.rootDir"), "app", "src", "main", "resources", "mixins.ssoptimizer.json");
        String content = Files.readString(config);
        assertTrue(content.contains("render"));
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*RenderMixinConfigTest'`
- Expected output:
  ```
  FAILED ... mixins.ssoptimizer.json does not contain render hook declarations
  ```

**Step 3: 增补 Mixin 配置与 Agent 绑定**
- File: `app/src/main/resources/mixins.ssoptimizer.json`
- Add rendering mixin entry points.
- File: `app/src/main/java/github/kasuminova/ssoptimizer/agent/SSOptimizerAgent.java`
- Ensure render-related mixins and processors are registered at premain.

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*RenderMixinConfigTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 6: 端到端验证

**Step 1: 运行全部测试**
- Command: `./gradlew :app:test`

**Step 2: 运行打包验证**
- Command: `./gradlew :app:jar`

**Step 3: 检查产物包含 Agent 与渲染资源**
- Verify `SSOptimizerAgent.class`、`RenderStateCache.class`、`mixins.ssoptimizer.json` 位于最终 JAR 中。

**Step 4: 根据结果修复最后的集成问题**
- 若失败，优先修复打包/类路径问题，再回到对应任务补测。
