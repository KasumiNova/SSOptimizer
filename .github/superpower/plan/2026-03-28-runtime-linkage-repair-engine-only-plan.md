# Runtime Linkage Repair Implementation Plan（Engine-only）

**Goal:** 打通 SSOptimizer 的运行时执行链路，但第一阶段只覆写 Starsector 原版引擎渲染类，让原版战斗渲染路径真正进入 `RenderStateCache -> EngineRenderBridge -> NativeRenderer`，为后续性能测试建立可信基线。

**Architecture:** `SSOptimizerAgent` 仅为原版引擎类注册精确 ASM 处理器：`com.fs.graphics.Sprite`、`com.fs.graphics.particle.GenericTextureParticle`、`com.fs.starfarer.combat.CombatState`。`RenderRuntimeBootstrap` 负责 native 加载与运行时开关；`EngineRenderBridge` 负责“原版类调用 -> 拦截 or 原样直通”的分流；`CombatState` 负责战斗帧开始/结束与 flush 边界。

**Tech Stack:** Java 25, JUnit 5, ASM 9.9, JNI, C++20, Gradle Kotlin DSL, Java Agent

---

### Task 1: 补齐运行时 bootstrap、native 解析与战斗帧边界

**Step 1: 写失败测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/runtime/RenderRuntimeBootstrapTest.java`
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/runtime/NativeLibraryResolverTest.java`
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/runtime/RenderFrameHooksTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderRuntimeBootstrapTest {
    @Test
    void bootstrapCanBeDisabledByProperty() {
        System.setProperty("ssoptimizer.render.enabled", "false");
        RenderRuntimeBootstrap.resetForTest();
        RenderRuntimeBootstrap.initialize();

        assertFalse(RenderRuntimeBootstrap.isPipelineEnabled());
        System.clearProperty("ssoptimizer.render.enabled");
    }

    @Test
    void traceModeReflectsProperty() {
        System.setProperty("ssoptimizer.trace", "true");
        RenderRuntimeBootstrap.resetForTest();
        RenderRuntimeBootstrap.initialize();

        assertTrue(RenderRuntimeBootstrap.isTraceEnabled());
        System.clearProperty("ssoptimizer.trace");
    }
}
```
```java
package github.kasuminova.ssoptimizer.render.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeLibraryResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesNativeLibraryFromModFolder() throws Exception {
        Path modsDir = tempDir.resolve("mods");
        Path nativeDir = modsDir.resolve("ssoptimizer").resolve("native").resolve("linux");
        Files.createDirectories(nativeDir);
        Path soFile = nativeDir.resolve(System.mapLibraryName("ssoptimizer"));
        Files.writeString(soFile, "stub");

        System.setProperty("com.fs.starfarer.settings.paths.mods", modsDir.toString());
        assertEquals(soFile.toAbsolutePath(), NativeLibraryResolver.resolve());
    }
}
```
```java
package github.kasuminova.ssoptimizer.render.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderFrameHooksTest {
    @Test
    void combatFrameStateCanToggle() {
        RenderFrameHooks.resetForTest();

        assertFalse(RenderFrameHooks.isCombatFrameActive());
        RenderFrameHooks.onCombatFrameStart();
        assertTrue(RenderFrameHooks.isCombatFrameActive());
        RenderFrameHooks.onCombatFrameEnd();
        assertFalse(RenderFrameHooks.isCombatFrameActive());
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*RenderRuntimeBootstrapTest' --tests '*NativeLibraryResolverTest' --tests '*RenderFrameHooksTest'`
- Expected output:
  ```
  FAILED ... bootstrap/runtime classes are missing
  ```

**Step 3: 实现最小运行时基建**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/runtime/NativeLibraryResolver.java`
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/runtime/RenderFrameHooks.java`
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/runtime/RenderRuntimeBootstrap.java`
- File: `app/src/main/java/github/kasuminova/ssoptimizer/SSOptimizerModPlugin.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

public final class NativeLibraryResolver {
    private NativeLibraryResolver() {
    }

    public static Path resolve() {
        String override = System.getProperty("ssoptimizer.native.path");
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override).toAbsolutePath();
            return Files.isRegularFile(path) ? path : null;
        }

        Path modsDir = Path.of(System.getProperty("com.fs.starfarer.settings.paths.mods", "./mods"));
        Path candidate = modsDir.resolve("ssoptimizer")
                .resolve("native")
                .resolve(platformFolder())
                .resolve(System.mapLibraryName("ssoptimizer"))
                .toAbsolutePath();

        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private static String platformFolder() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "mac";
        return "linux";
    }
}
```
```java
package github.kasuminova.ssoptimizer.render.runtime;

import github.kasuminova.ssoptimizer.render.intercept.GL11Interceptor;

public final class RenderFrameHooks {
    private static final ThreadLocal<Boolean> COMBAT_FRAME = ThreadLocal.withInitial(() -> false);

    private RenderFrameHooks() {
    }

    public static void onCombatFrameStart() {
        COMBAT_FRAME.set(true);
    }

    public static void onCombatFrameEnd() {
        GL11Interceptor.flushPending();
        COMBAT_FRAME.set(false);
    }

    public static boolean isCombatFrameActive() {
        return COMBAT_FRAME.get();
    }

    static void resetForTest() {
        COMBAT_FRAME.remove();
    }
}
```
```java
package github.kasuminova.ssoptimizer.render.runtime;

import github.kasuminova.ssoptimizer.render.batch.NativeRenderer;
import github.kasuminova.ssoptimizer.render.intercept.GL11Interceptor;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RenderRuntimeBootstrap {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static volatile boolean pipelineEnabled;
    private static volatile boolean traceEnabled;

    private RenderRuntimeBootstrap() {
    }

    public static void initialize() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }

        traceEnabled = Boolean.getBoolean("ssoptimizer.trace");
        if (!Boolean.parseBoolean(System.getProperty("ssoptimizer.render.enabled", "true"))) {
            pipelineEnabled = false;
            return;
        }

        GL11Interceptor.init(Integer.getInteger("ssoptimizer.buffer.commands", 4096));

        Path nativePath = NativeLibraryResolver.resolve();
        if (nativePath != null && NativeRenderer.loadLibrary(nativePath.toString())) {
            pipelineEnabled = true;
            System.out.println("[SSOptimizer] Runtime bootstrap ready: " + nativePath);
        } else {
            pipelineEnabled = false;
            System.out.println("[SSOptimizer] Runtime bootstrap fallback: native library unavailable");
        }
    }

    public static boolean isPipelineEnabled() {
        return pipelineEnabled;
    }

    public static boolean isTraceEnabled() {
        return traceEnabled;
    }

    static void resetForTest() {
        INITIALIZED.set(false);
        pipelineEnabled = false;
        traceEnabled = false;
    }
}
```
```java
package github.kasuminova.ssoptimizer;

import com.fs.starfarer.api.BaseModPlugin;
import github.kasuminova.ssoptimizer.render.runtime.RenderRuntimeBootstrap;

public class SSOptimizerModPlugin extends BaseModPlugin {
    @Override
    public void onApplicationLoad() {
        RenderRuntimeBootstrap.initialize();
        System.out.println("[SSOptimizer] Loaded on Java " + Runtime.version());
    }
}
```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*RenderRuntimeBootstrapTest' --tests '*NativeLibraryResolverTest' --tests '*RenderFrameHooksTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 2: 增加“原版引擎专用”桥接层，而不是全局接管 `GL11`

**Step 1: 写失败测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/engine/EngineRenderBridgeTest.java`
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/intercept/GL11InterceptorFlushTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.engine;

import github.kasuminova.ssoptimizer.render.runtime.RenderFrameHooks;
import github.kasuminova.ssoptimizer.render.runtime.RenderRuntimeBootstrap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EngineRenderBridgeTest {
    @Test
    void routesToInterceptorOnlyInsideCombatFrameWhenPipelineEnabled() {
        RecordingGlBackend backend = new RecordingGlBackend();
        EngineRenderBridge.setBackendForTest(backend);

        RenderRuntimeBootstrap.forceEnabledForTest();
        RenderFrameHooks.onCombatFrameStart();

        EngineRenderBridge.glBindTexture(3553, 42);
        EngineRenderBridge.glBegin(7);
        EngineRenderBridge.glEnd();

        assertEquals(0, backend.bindTextureCalls);
        assertEquals(0, backend.beginCalls);

        RenderFrameHooks.onCombatFrameEnd();
        EngineRenderBridge.resetBackendForTest();
        RenderRuntimeBootstrap.resetForTest();
    }

    @Test
    void passthroughsWhenPipelineDisabled() {
        RecordingGlBackend backend = new RecordingGlBackend();
        EngineRenderBridge.setBackendForTest(backend);

        RenderRuntimeBootstrap.resetForTest();
        EngineRenderBridge.glBindTexture(3553, 42);

        assertEquals(1, backend.bindTextureCalls);

        EngineRenderBridge.resetBackendForTest();
    }
}
```
```java
package github.kasuminova.ssoptimizer.render.intercept;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GL11InterceptorFlushTest {
    @AfterEach
    void tearDown() {
        GL11Interceptor.shutdown();
    }

    @Test
    void flushPendingClearsQueuedCommands() {
        GL11Interceptor.init(8);
        GL11Interceptor.glBegin(0x0007);
        GL11Interceptor.glVertex2f(0, 0);
        GL11Interceptor.glVertex2f(1, 0);
        GL11Interceptor.glVertex2f(1, 1);
        GL11Interceptor.glVertex2f(0, 1);
        GL11Interceptor.glEnd();

        assertEquals(1, GL11Interceptor.pendingCount());
        GL11Interceptor.flushPending();
        assertEquals(0, GL11Interceptor.pendingCount());
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*EngineRenderBridgeTest' --tests '*GL11InterceptorFlushTest'`
- Expected output:
  ```
  FAILED ... EngineRenderBridge / flushPending missing
  ```

**Step 3: 实现桥接与 flush**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/engine/GlBackend.java`
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/engine/DefaultGlBackend.java`
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/engine/EngineRenderBridge.java`
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/intercept/GL11Interceptor.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.engine;

public interface GlBackend {
    void glBegin(int mode);
    void glEnd();
    void glBindTexture(int target, int textureId);
    void glBlendFunc(int src, int dst);
    void glColor4f(float r, float g, float b, float a);
    void glTexCoord2f(float u, float v);
    void glVertex2f(float x, float y);
}
```
```java
package github.kasuminova.ssoptimizer.render.engine;

import org.lwjgl.opengl.GL11;

public final class DefaultGlBackend implements GlBackend {
    @Override public void glBegin(int mode) { GL11.glBegin(mode); }
    @Override public void glEnd() { GL11.glEnd(); }
    @Override public void glBindTexture(int target, int textureId) { GL11.glBindTexture(target, textureId); }
    @Override public void glBlendFunc(int src, int dst) { GL11.glBlendFunc(src, dst); }
    @Override public void glColor4f(float r, float g, float b, float a) { GL11.glColor4f(r, g, b, a); }
    @Override public void glTexCoord2f(float u, float v) { GL11.glTexCoord2f(u, v); }
    @Override public void glVertex2f(float x, float y) { GL11.glVertex2f(x, y); }
}
```
```java
package github.kasuminova.ssoptimizer.render.engine;

import github.kasuminova.ssoptimizer.render.intercept.GL11Interceptor;
import github.kasuminova.ssoptimizer.render.runtime.RenderFrameHooks;
import github.kasuminova.ssoptimizer.render.runtime.RenderRuntimeBootstrap;

public final class EngineRenderBridge {
    private static volatile GlBackend backend = new DefaultGlBackend();

    private EngineRenderBridge() {
    }

    public static void glBegin(int mode) {
        if (shouldIntercept()) {
            GL11Interceptor.glBegin(mode);
            return;
        }
        backend.glBegin(mode);
    }

    public static void glEnd() {
        if (shouldIntercept()) {
            GL11Interceptor.glEnd();
            return;
        }
        backend.glEnd();
    }

    public static void glBindTexture(int target, int textureId) {
        if (shouldIntercept()) {
            GL11Interceptor.glBindTexture(target, textureId);
            return;
        }
        backend.glBindTexture(target, textureId);
    }

    public static void glBlendFunc(int src, int dst) {
        if (shouldIntercept()) {
            GL11Interceptor.glBlendFunc(src, dst);
            return;
        }
        backend.glBlendFunc(src, dst);
    }

    public static void glColor4f(float r, float g, float b, float a) {
        if (shouldIntercept()) {
            GL11Interceptor.glColor4f(r, g, b, a);
            return;
        }
        backend.glColor4f(r, g, b, a);
    }

    public static void glTexCoord2f(float u, float v) {
        if (shouldIntercept()) {
            GL11Interceptor.glTexCoord2f(u, v);
            return;
        }
        backend.glTexCoord2f(u, v);
    }

    public static void glVertex2f(float x, float y) {
        if (shouldIntercept()) {
            GL11Interceptor.glVertex2f(x, y);
            return;
        }
        backend.glVertex2f(x, y);
    }

    static void setBackendForTest(GlBackend testBackend) {
        backend = testBackend;
    }

    static void resetBackendForTest() {
        backend = new DefaultGlBackend();
    }

    private static boolean shouldIntercept() {
        return RenderRuntimeBootstrap.isPipelineEnabled() && RenderFrameHooks.isCombatFrameActive();
    }
}
```
```java
package github.kasuminova.ssoptimizer.render.intercept;

import github.kasuminova.ssoptimizer.render.batch.NativeCommandBuffer;
import github.kasuminova.ssoptimizer.render.batch.NativeRenderer;
import github.kasuminova.ssoptimizer.render.batch.RenderCommand;
import github.kasuminova.ssoptimizer.render.runtime.RenderTelemetry;

public final class GL11Interceptor {
    private static NativeCommandBuffer buffer;
    private static final ThreadLocal<CommandState> STATE = ThreadLocal.withInitial(CommandState::new);

    private GL11Interceptor() {
    }

    public static void init(int maxCommands) {
        if (buffer != null) {
            buffer.destroy();
        }
        buffer = new NativeCommandBuffer(maxCommands);
        STATE.set(new CommandState());
    }

    public static void shutdown() {
        if (buffer != null) {
            buffer.destroy();
            buffer = null;
        }
        STATE.remove();
    }

    public static void glBegin(int mode) {
        RenderTelemetry.recordIntercept("glBegin");
        CommandState state = STATE.get();
        state.accumulating = true;
        state.vertexIndex = 0;
        state.current = new RenderCommand();
        state.current.textureId = state.textureId;
        state.current.blendSrc = state.blendSrc;
        state.current.blendDst = state.blendDst;
        state.current.setColor(state.r, state.g, state.b, state.a);
    }

    public static void glEnd() {
        RenderTelemetry.recordIntercept("glEnd");
        CommandState state = STATE.get();
        state.accumulating = false;
        if (buffer != null && state.current != null) {
            buffer.append(state.current);
            RenderTelemetry.recordQueuedCommand();
            if (buffer.isFull()) {
                NativeRenderer.flush(buffer);
            }
        }
        state.current = null;
    }

    public static void glVertex2f(float x, float y) {
        RenderTelemetry.recordIntercept("glVertex2f");
        CommandState state = STATE.get();
        if (state.current != null && state.vertexIndex < 4) {
            state.current.setVertex(state.vertexIndex, x, y, 0.0f, 0.0f);
            state.vertexIndex++;
        }
    }

    public static void glTexCoord2f(float u, float v) {
        RenderTelemetry.recordIntercept("glTexCoord2f");
        CommandState state = STATE.get();
        if (state.current != null && state.vertexIndex < 4) {
            state.current.u[state.vertexIndex] = u;
            state.current.v[state.vertexIndex] = v;
        }
    }

    public static void glBindTexture(int target, int textureId) {
        RenderTelemetry.recordIntercept("glBindTexture");
        CommandState state = STATE.get();
        state.textureId = textureId;
    }

    public static void glBlendFunc(int src, int dst) {
        RenderTelemetry.recordIntercept("glBlendFunc");
        CommandState state = STATE.get();
        state.blendSrc = src;
        state.blendDst = dst;
    }

    public static void glColor4f(float r, float g, float b, float a) {
        RenderTelemetry.recordIntercept("glColor4f");
        CommandState state = STATE.get();
        state.r = r;
        state.g = g;
        state.b = b;
        state.a = a;
    }

    public static void flushPending() {
        if (buffer != null && buffer.count() > 0) {
            NativeRenderer.flush(buffer);
        }
    }

    public static boolean isAccumulating() {
        return STATE.get().accumulating;
    }

    public static int pendingCount() {
        return buffer == null ? 0 : buffer.count();
    }

    private static final class CommandState {
        private boolean accumulating;
        private int textureId = -1;
        private int blendSrc;
        private int blendDst;
        private float r = 1.0f;
        private float g = 1.0f;
        private float b = 1.0f;
        private float a = 1.0f;
        private int vertexIndex;
        private RenderCommand current;
    }
}
```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*EngineRenderBridgeTest' --tests '*GL11InterceptorFlushTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 3: 只覆写原版 `Sprite`，不碰任何第三方 Mod 类

**Step 1: 写失败测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/engine/EngineSpriteProcessorTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.engine;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineSpriteProcessorTest {
    private byte[] createFakeSpriteClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/fs/graphics/Sprite", null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "render", "(FF)V", null, null);
        mv.visitCode();
        mv.visitIntInsn(Opcodes.SIPUSH, 3553);
        mv.visitIntInsn(Opcodes.SIPUSH, 42);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glBindTexture", "(II)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 3);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void rewritesOnlySpriteGlCallsToEngineBridge() {
        byte[] rewritten = new EngineSpriteProcessor().process(createFakeSpriteClass());
        assertNotNull(rewritten);

        ClassReader reader = new ClassReader(rewritten);
        final boolean[] foundBridge = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (owner.contains("EngineRenderBridge") && methodName.equals("glBindTexture")) {
                            foundBridge[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(foundBridge[0]);
    }

    @Test
    void ignoresNonSpriteClasses() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Other", null, "java/lang/Object", null);
        cw.visitEnd();

        assertNull(new EngineSpriteProcessor().process(cw.toByteArray()));
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*EngineSpriteProcessorTest'`
- Expected output:
  ```
  FAILED ... EngineSpriteProcessor missing
  ```

**Step 3: 实现 `EngineSpriteProcessor`**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/engine/EngineSpriteProcessor.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.engine;

import github.kasuminova.ssoptimizer.agent.AsmClassProcessor;
import org.objectweb.asm.*;

import java.util.Set;

public final class EngineSpriteProcessor implements AsmClassProcessor {
    private static final String TARGET_CLASS = "com/fs/graphics/Sprite";
    private static final String GL11_OWNER = "org/lwjgl/opengl/GL11";
    private static final String BRIDGE_OWNER = "github/kasuminova/ssoptimizer/render/engine/EngineRenderBridge";
    private static final Set<String> TARGET_METHODS = Set.of("render", "renderRegion");
    private static final Set<String> GL_METHODS = Set.of(
            "glBegin", "glEnd", "glBindTexture", "glBlendFunc", "glColor4f", "glTexCoord2f", "glVertex2f"
    );

    @Override
    public byte[] process(byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, sig, ex);
                if (!TARGET_METHODS.contains(name)) {
                    return delegate;
                }

                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (owner.equals(GL11_OWNER) && GL_METHODS.contains(methodName)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER, methodName, methodDesc, false);
                            modified[0] = true;
                        } else {
                            super.visitMethodInsn(opcode, owner, methodName, methodDesc, itf);
                        }
                    }
                };
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}
```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*EngineSpriteProcessorTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 4: 只覆写原版 `GenericTextureParticle`

**Step 1: 写失败测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/engine/EngineParticleProcessorTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.engine;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineParticleProcessorTest {
    private byte[] createFakeParticleClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/fs/graphics/particle/GenericTextureParticle", null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "render", "()V", null, null);
        mv.visitCode();
        mv.visitIntInsn(Opcodes.SIPUSH, 3553);
        mv.visitIntInsn(Opcodes.SIPUSH, 99);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glBindTexture", "(II)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void rewritesParticleGlCallsToEngineBridge() {
        byte[] rewritten = new EngineParticleProcessor().process(createFakeParticleClass());
        assertNotNull(rewritten);

        ClassReader reader = new ClassReader(rewritten);
        final boolean[] foundBridge = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (owner.contains("EngineRenderBridge") && methodName.equals("glBindTexture")) {
                            foundBridge[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(foundBridge[0]);
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*EngineParticleProcessorTest'`
- Expected output:
  ```
  FAILED ... EngineParticleProcessor missing
  ```

**Step 3: 实现 `EngineParticleProcessor`**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/engine/EngineParticleProcessor.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.engine;

import github.kasuminova.ssoptimizer.agent.AsmClassProcessor;
import org.objectweb.asm.*;

import java.util.Set;

public final class EngineParticleProcessor implements AsmClassProcessor {
    private static final String TARGET_CLASS = "com/fs/graphics/particle/GenericTextureParticle";
    private static final String GL11_OWNER = "org/lwjgl/opengl/GL11";
    private static final String BRIDGE_OWNER = "github/kasuminova/ssoptimizer/render/engine/EngineRenderBridge";
    private static final Set<String> GL_METHODS = Set.of(
            "glBegin", "glEnd", "glBindTexture", "glBlendFunc", "glColor4f", "glTexCoord2f", "glVertex2f"
    );

    @Override
    public byte[] process(byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, sig, ex);
                if (!name.equals("render")) {
                    return delegate;
                }

                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (owner.equals(GL11_OWNER) && GL_METHODS.contains(methodName)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_OWNER, methodName, methodDesc, false);
                            modified[0] = true;
                        } else {
                            super.visitMethodInsn(opcode, owner, methodName, methodDesc, itf);
                        }
                    }
                };
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}
```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*EngineParticleProcessorTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 5: 只覆写原版 `CombatState.traverse`，补齐帧开始/结束与 `glFinish` 旁路

**Step 1: 写失败测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/engine/CombatStateProcessorTest.java`
- File: `app/src/test/java/github/kasuminova/ssoptimizer/agent/EngineProcessorRegistrationTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.engine;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatStateProcessorTest {
    private byte[] createFakeCombatState() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/fs/starfarer/combat/CombatState", null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "traverse", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glFinish", "()V", false);
        mv.visitLdcInsn("ok");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void injectsCombatFrameHooksAndRedirectsFinish() {
        byte[] rewritten = new CombatStateProcessor().process(createFakeCombatState());
        assertNotNull(rewritten);

        ClassReader reader = new ClassReader(rewritten);
        final boolean[] foundStart = {false};
        final boolean[] foundEnd = {false};
        final boolean[] foundFinishHook = {false};

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (owner.contains("RenderFrameHooks") && methodName.equals("onCombatFrameStart")) foundStart[0] = true;
                        if (owner.contains("RenderFrameHooks") && methodName.equals("onCombatFrameEnd")) foundEnd[0] = true;
                        if (owner.contains("CombatStateTraversalHook") && methodName.equals("callFinishIfEnabled")) foundFinishHook[0] = true;
                    }
                };
            }
        }, 0);

        assertTrue(foundStart[0]);
        assertTrue(foundEnd[0]);
        assertTrue(foundFinishHook[0]);
    }
}
```
```java
package github.kasuminova.ssoptimizer.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EngineProcessorRegistrationTest {
    @Test
    void agentRegistersOnlyEngineProcessorsForRuntimeRepairPhase() {
        HybridWeaverTransformer transformer = new HybridWeaverTransformer();
        transformer.registerProcessor("com.fs.graphics.Sprite", bytes -> bytes);
        transformer.registerProcessor("com.fs.graphics.particle.GenericTextureParticle", bytes -> bytes);
        transformer.registerProcessor("com.fs.starfarer.combat.CombatState", bytes -> bytes);

        assertEquals(3, transformer.getProcessorCount());
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*CombatStateProcessorTest' --tests '*EngineProcessorRegistrationTest'`
- Expected output:
  ```
  FAILED ... CombatStateProcessor missing or not wiring frame hooks
  ```

**Step 3: 实现 `CombatStateProcessor` 并注册**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/engine/CombatStateProcessor.java`
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/CombatStateTraversalHook.java`
- File: `app/src/main/java/github/kasuminova/ssoptimizer/agent/SSOptimizerAgent.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.engine;

import github.kasuminova.ssoptimizer.agent.AsmClassProcessor;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

public final class CombatStateProcessor implements AsmClassProcessor {
    private static final String TARGET_CLASS = "com/fs/starfarer/combat/CombatState";
    private static final String GL11_OWNER = "org/lwjgl/opengl/GL11";
    private static final String FRAME_HOOK_OWNER = "github/kasuminova/ssoptimizer/render/runtime/RenderFrameHooks";
    private static final String FINISH_HOOK_OWNER = "github/kasuminova/ssoptimizer/render/CombatStateTraversalHook";

    @Override
    public byte[] process(byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, sig, ex);
                if (!name.equals("traverse")) {
                    return delegate;
                }

                modified[0] = true;
                return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, desc) {
                    @Override
                    protected void onMethodEnter() {
                        visitMethodInsn(INVOKESTATIC, FRAME_HOOK_OWNER, "onCombatFrameStart", "()V", false);
                    }

                    @Override
                    protected void onMethodExit(int opcode) {
                        visitMethodInsn(INVOKESTATIC, FRAME_HOOK_OWNER, "onCombatFrameEnd", "()V", false);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (owner.equals(GL11_OWNER) && methodName.equals("glFinish") && methodDesc.equals("()V")) {
                            super.visitMethodInsn(INVOKESTATIC, FINISH_HOOK_OWNER, "callFinishIfEnabled", "()V", false);
                        } else {
                            super.visitMethodInsn(opcode, owner, methodName, methodDesc, itf);
                        }
                    }
                };
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}
```
```java
package github.kasuminova.ssoptimizer.render;

import org.lwjgl.opengl.GL11;

public final class CombatStateTraversalHook {
    private CombatStateTraversalHook() {
    }

    public static boolean shouldCallFinish(boolean enabled) {
        return enabled;
    }

    public static void callFinishIfEnabled() {
        if (shouldCallFinish(Boolean.getBoolean("ssoptimizer.render.allowFinish"))) {
            GL11.glFinish();
        }
    }
}
```
```java
package github.kasuminova.ssoptimizer.agent;

import github.kasuminova.ssoptimizer.render.engine.CombatStateProcessor;
import github.kasuminova.ssoptimizer.render.engine.EngineParticleProcessor;
import github.kasuminova.ssoptimizer.render.engine.EngineSpriteProcessor;
import github.kasuminova.ssoptimizer.render.intercept.GL11RewriteProcessor;
import github.kasuminova.ssoptimizer.render.runtime.RenderRuntimeBootstrap;
import github.kasuminova.ssoptimizer.render.intercept.ModWhitelist;

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
        weaverTransformer.registerProcessor("com.fs.graphics.Sprite", new EngineSpriteProcessor());
        weaverTransformer.registerProcessor("com.fs.graphics.particle.GenericTextureParticle", new EngineParticleProcessor());
        weaverTransformer.registerProcessor("com.fs.starfarer.combat.CombatState", new CombatStateProcessor());
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

        System.out.println("[SSOptimizer] Agent loaded — Engine-only repair phase active");
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
- Command: `./gradlew :app:test --tests '*CombatStateProcessorTest' --tests '*EngineProcessorRegistrationTest' --tests '*CombatStateTraversalHookTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 6: 部署到真实游戏目录并做引擎链路 smoke test

**Step 1: 增加开发安装任务**
- File: `build.gradle.kts`
- Code:
```kotlin
import org.gradle.api.tasks.Copy

tasks.register<Copy>("installDevMod") {
    group = "dev workflow"
    description = "Install SSOptimizer jar and native runtime into the Starsector dev mod folder"

    dependsOn(":app:jar", ":native:assemble")

    val starsectorGameDir = providers.gradleProperty("starsector.gameDir").orNull
        ?: error("Provide -Pstarsector.gameDir=/path/to/Starsector")

    val gameDir = file(starsectorGameDir)
    val modDir = gameDir.resolve("mods/ssoptimizer")

    from(project(":app").layout.buildDirectory.file("libs/SSOptimizer.jar")) {
        into("jars")
    }

    from(project(":native").layout.buildDirectory.dir("lib")) {
        include("**/*.so")
        into("native/linux")
    }

    into(modDir)
}
```

**Step 2: 构建并安装到当前 Starsector 目录**
- Command: `./gradlew -Pstarsector.gameDir=/mnt/windows_data/Games/Starsector098-linux :app:test :native:assemble :app:jar installDevMod`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

**Step 3: 启动游戏进行 smoke test**
- Command: `cd /mnt/windows_data/Games/Starsector098-linux && ./launch_debug_auto.sh`
- Expected output:
  ```
  Starting Starsector with debug port 5005...
  ```

**Step 4: 检查日志，只要求看到“原版引擎链路”激活证据**
- Command: `grep -E '\[SSOptimizer\]|\[SSOptimizer-Native\]' /mnt/windows_data/Games/Starsector098-linux/starsector.log | tail -50`
- Expected output 至少包含：
  ```
  [SSOptimizer] Runtime bootstrap ready: ...
  [SSOptimizer] Loaded on Java ...
  [SSOptimizer-Native] Renderer initialized
  ```

**Step 5: Safe 模式回归**
- 修改 JVM 参数追加：`-Dssoptimizer.render.enabled=false`
- 再次启动后 Expected output：
  ```
  [SSOptimizer] Runtime bootstrap fallback: native library unavailable
  ```
  或
  ```
  [SSOptimizer] Runtime bootstrap disabled by property
  ```

**Step 6: Trace 模式验证**
- 修改 JVM 参数追加：`-Dssoptimizer.trace=true`
- 期望日志出现：
  - 战斗帧进入/退出统计
  - 已入队命令数
  - flush 次数
  - `glFinish` 跳过次数

---

## 这版计划的范围声明

这次第一阶段**只做原版引擎类覆写**，明确不做：

- 第三方 Mod 白名单
- 第三方 Mod 类扫描
- 全局 `GL11` 调用方接管
- 通用兼容策略前置

第一阶段目标只有一个：

> **先让原版战斗渲染链路真的跑通，并且可以被验证。**

---

## 完成标准

当以下条件都满足时，才进入性能测试：

- `SSOptimizerAgent` 在运行时真实加载
- `com.fs.graphics.Sprite` 已被覆写并经由 `EngineRenderBridge` 进入拦截链路
- `com.fs.graphics.particle.GenericTextureParticle` 已被覆写
- `com.fs.starfarer.combat.CombatState` 已能控制战斗帧开始/结束和 `glFinish` 旁路
- `NativeRenderer` 已能成功加载 native 库并执行 flush
- 日志中能看到 SSOptimizer 的激活证据和遥测信息
- 运行 `Safe` 与 `Trace` 模式能稳定区分基线与修链效果
