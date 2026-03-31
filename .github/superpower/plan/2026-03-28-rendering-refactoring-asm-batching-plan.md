# 渲染重构实现计划（Phase C：ASM 批处理接管）

**Goal:** 将 Starsector 的高频立即模式渲染转化为可批处理的原生命令队列，并通过 ASM 全局接管 `GL11` 裸调，降低 JNI 与状态切换开销，同时保留 Mod 兼容白名单回退能力。

**Architecture:** Java 端使用 DirectByteBuffer 构建 `RenderCommand` 命令队列，`NativeRenderer` 通过 JNI 将队列一次性下发到 C++ 端；C++ 端执行状态重排与批渲染；ASM `ClassFileTransformer` 负责重写 `org.lwjgl.opengl.GL11` 调用点，白名单用于跳过高风险模组。

**Tech Stack:** Java 25, JUnit 5, Gradle Kotlin DSL, ASM 9.9, JNI, C++20, Sponge Mixin（保留现有注入基建）

---

### Task 1: 定义渲染命令协议 `RenderCommand`

**Step 1: 写失败测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/batch/RenderCommandTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.batch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RenderCommandTest {
    @Test
    void exposesExpectedStructSize() {
        assertEquals(64, RenderCommand.STRUCT_SIZE);
    }

    @Test
    void storesTextureBlendAndColorState() {
        RenderCommand command = new RenderCommand();
        command.textureId = 42;
        command.blendSrc = 770;
        command.blendDst = 771;
        command.setColor(1.0f, 0.5f, 0.25f, 1.0f);

        assertEquals(42, command.textureId);
        assertEquals(770, command.blendSrc);
        assertEquals(771, command.blendDst);
        assertNotNull(command.x);
    }

    @Test
    void storesFourVertices() {
        RenderCommand command = new RenderCommand();
        command.setVertex(0, 1.0f, 2.0f, 0.0f, 0.0f);
        command.setVertex(3, 8.0f, 9.0f, 1.0f, 1.0f);

        assertEquals(1.0f, command.x[0]);
        assertEquals(2.0f, command.y[0]);
        assertEquals(8.0f, command.x[3]);
        assertEquals(9.0f, command.y[3]);
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*RenderCommandTest'`
- Expected output:
  ```
  FAILED ... RenderCommand cannot be resolved
  ```

**Step 3: 实现最小命令对象**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/batch/RenderCommand.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.batch;

public final class RenderCommand {
    public static final int STRUCT_SIZE = 64;

    public int textureId = -1;
    public int blendSrc;
    public int blendDst;
    public float r = 1.0f;
    public float g = 1.0f;
    public float b = 1.0f;
    public float a = 1.0f;
    public final float[] x = new float[4];
    public final float[] y = new float[4];
    public final float[] u = new float[4];
    public final float[] v = new float[4];

    public void setVertex(int index, float px, float py, float tu, float tv) {
        x[index] = px;
        y[index] = py;
        u[index] = tu;
        v[index] = tv;
    }

    public void setColor(float r, float g, float b, float a) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }
}
```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*RenderCommandTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 2: 实现命令缓冲区 `NativeCommandBuffer`

**Step 1: 写失败测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/batch/NativeCommandBufferTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.batch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeCommandBufferTest {
    private NativeCommandBuffer buffer;

    @AfterEach
    void tearDown() {
        if (buffer != null) {
            buffer.destroy();
        }
    }

    @Test
    void allocatesDirectMemoryAndTracksCapacity() {
        buffer = new NativeCommandBuffer(128);
        assertEquals(128, buffer.capacity());
        assertEquals(0, buffer.count());
        assertTrue(buffer.address() != 0L);
    }

    @Test
    void appendIncreasesCount() {
        buffer = new NativeCommandBuffer(8);
        buffer.append(new RenderCommand());
        assertEquals(1, buffer.count());
    }

    @Test
    void resetClearsCount() {
        buffer = new NativeCommandBuffer(8);
        buffer.append(new RenderCommand());
        buffer.reset();
        assertEquals(0, buffer.count());
    }

    @Test
    void isFullWhenCapacityReached() {
        buffer = new NativeCommandBuffer(2);
        buffer.append(new RenderCommand());
        buffer.append(new RenderCommand());
        assertTrue(buffer.isFull());
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*NativeCommandBufferTest'`
- Expected output:
  ```
  FAILED ... NativeCommandBuffer cannot be resolved
  ```

**Step 3: 实现最小缓冲区**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/batch/NativeCommandBuffer.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.batch;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class NativeCommandBuffer {
    private final ByteBuffer buffer;
    private final int capacity;
    private int count;

    public NativeCommandBuffer(int maxCommands) {
        this.capacity = maxCommands;
        this.buffer = ByteBuffer.allocateDirect(maxCommands * RenderCommand.STRUCT_SIZE)
                .order(ByteOrder.nativeOrder());
    }

    public void append(RenderCommand command) {
        if (count >= capacity) {
            throw new IllegalStateException("Command buffer overflow");
        }
        int offset = count * RenderCommand.STRUCT_SIZE;
        buffer.putInt(offset, command.textureId);
        buffer.putInt(offset + 4, command.blendSrc);
        buffer.putInt(offset + 8, command.blendDst);
        buffer.putFloat(offset + 12, command.r);
        buffer.putFloat(offset + 16, command.g);
        buffer.putFloat(offset + 20, command.b);
        buffer.putFloat(offset + 24, command.a);
        for (int i = 0; i < 4; i++) {
            int vertexOffset = offset + 28 + i * 16;
            buffer.putFloat(vertexOffset, command.x[i]);
            buffer.putFloat(vertexOffset + 4, command.y[i]);
            buffer.putFloat(vertexOffset + 8, command.u[i]);
            buffer.putFloat(vertexOffset + 12, command.v[i]);
        }
        count++;
    }

    public void reset() {
        count = 0;
    }

    public int count() {
        return count;
    }

    public int capacity() {
        return capacity;
    }

    public boolean isFull() {
        return count >= capacity;
    }

    public long address() {
        try {
            var field = buffer.getClass().getDeclaredField("address");
            field.setAccessible(true);
            return (long) field.get(buffer);
        } catch (ReflectiveOperationException e) {
            return 0L;
        }
    }

    public ByteBuffer rawBuffer() {
        return buffer;
    }

    public void destroy() {
        // 预留给后续 Unsafe/cleaner 路径；当前依赖 GC 回收 DirectByteBuffer。
    }
}
```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*NativeCommandBufferTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 3: 实现 JNI 桥接 `NativeRenderer`

**Step 1: 写失败测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/batch/NativeRendererTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.batch;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeRendererTest {
    @Test
    void declaresNativeFlushAndInitMethods() throws Exception {
        Method flush = NativeRenderer.class.getDeclaredMethod("nativeFlushQueue", long.class, int.class);
        Method init = NativeRenderer.class.getDeclaredMethod("nativeInit");

        assertTrue(Modifier.isNative(flush.getModifiers()));
        assertTrue(Modifier.isNative(init.getModifiers()));
    }

    @Test
    void flushIsSafeWhenNativeLibraryIsUnavailable() {
        NativeCommandBuffer buffer = new NativeCommandBuffer(4);
        buffer.append(new RenderCommand());

        assertDoesNotThrow(() -> NativeRenderer.flush(buffer));
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*NativeRendererTest'`
- Expected output:
  ```
  FAILED ... NativeRenderer cannot be resolved
  ```

**Step 3: 实现最小 JNI 桥接**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/batch/NativeRenderer.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.batch;

public final class NativeRenderer {
    private static volatile boolean nativeLoaded;

    private NativeRenderer() {
    }

    public static void loadLibrary(String libraryPath) {
        try {
            System.load(libraryPath);
            nativeInit();
            nativeLoaded = true;
        } catch (UnsatisfiedLinkError error) {
            nativeLoaded = false;
            System.err.println("[SSOptimizer] Native renderer unavailable: " + error.getMessage());
        }
    }

    public static void flush(NativeCommandBuffer buffer) {
        if (!nativeLoaded || buffer == null || buffer.count() == 0) {
            return;
        }
        nativeFlushQueue(buffer.address(), buffer.count());
        buffer.reset();
    }

    public static boolean isNativeLoaded() {
        return nativeLoaded;
    }

    private static native void nativeFlushQueue(long bufferAddress, int commandCount);
    private static native void nativeInit();
}
```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*NativeRendererTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 4: C++ 端镜像结构与 JNI 入口

**Step 1: 生成 JNI 头文件**
- Command: `./gradlew :app:compileJava`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```
- 结果：`native/src/main/headers/generated/` 下生成 `NativeRenderer` 对应 JNI 头文件。

**Step 2: 写失败的 native 结构体测试/静态检查**
- File: `native/src/main/cpp/native_renderer.cpp`
- 先补齐一个最小可编译实现，并在编译失败前暴露缺失符号。

**Step 3: 实现 C++ 结构体与 JNI 桩**
- File: `native/src/main/headers/ssoptimizer/render_command.h`
```cpp
#pragma once

#include <cstdint>

namespace ssoptimizer {

struct alignas(4) RenderCommand {
    int32_t textureId;
    int32_t blendSrc;
    int32_t blendDst;
    float r, g, b, a;
    struct Vertex {
        float x, y, u, v;
    } vertices[4];
};

static_assert(sizeof(RenderCommand) == 96, "RenderCommand size mismatch");

} // namespace ssoptimizer
```
- File: `native/src/main/cpp/native_renderer.cpp`
```cpp
#include <jni.h>
#include <cstdint>
#include <iostream>
#include "ssoptimizer/render_command.h"

static int32_t lastTextureId = -1;
static int32_t lastBlendSrc = -1;
static int32_t lastBlendDst = -1;

static void flushCommands(const ssoptimizer::RenderCommand* commands, jint count) {
    for (jint i = 0; i < count; ++i) {
        const auto& command = commands[i];
        if (command.textureId != lastTextureId) {
            lastTextureId = command.textureId;
        }
        if (command.blendSrc != lastBlendSrc || command.blendDst != lastBlendDst) {
            lastBlendSrc = command.blendSrc;
            lastBlendDst = command.blendDst;
        }
        // 后续阶段在这里接入 glDrawArrays / VBO 提交。
    }
}

extern "C" {

JNIEXPORT void JNICALL Java_github_kasuminova_ssoptimizer_render_batch_NativeRenderer_nativeInit
  (JNIEnv*, jclass) {
    lastTextureId = -1;
    lastBlendSrc = -1;
    lastBlendDst = -1;
    std::cout << "[SSOptimizer-Native] Renderer initialized" << std::endl;
}

JNIEXPORT void JNICALL Java_github_kasuminova_ssoptimizer_render_batch_NativeRenderer_nativeFlushQueue
  (JNIEnv*, jclass, jlong bufferAddress, jint commandCount) {
    auto* commands = reinterpret_cast<const ssoptimizer::RenderCommand*>(bufferAddress);
    flushCommands(commands, commandCount);
}

} // extern "C"
```

**Step 4: 编译 native 模块并确认通过**
- Command: `./gradlew :native:assemble`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 5: 实现 GL11 运行时拦截层 `GL11Interceptor`

**Step 1: 写失败测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/intercept/GL11InterceptorTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.intercept;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GL11InterceptorTest {
    @BeforeEach
    void setUp() {
        GL11Interceptor.init(1024);
    }

    @AfterEach
    void tearDown() {
        GL11Interceptor.shutdown();
    }

    @Test
    void glBeginStartsAccumulation() {
        GL11Interceptor.glBegin(0x0007);
        assertTrue(GL11Interceptor.isAccumulating());
    }

    @Test
    void glEndFinalizesCommand() {
        GL11Interceptor.glBegin(0x0007);
        GL11Interceptor.glVertex2f(0, 0);
        GL11Interceptor.glVertex2f(1, 0);
        GL11Interceptor.glVertex2f(1, 1);
        GL11Interceptor.glVertex2f(0, 1);
        GL11Interceptor.glEnd();

        assertFalse(GL11Interceptor.isAccumulating());
        assertEquals(1, GL11Interceptor.pendingCount());
    }

    @Test
    void glBindTextureUpdatesState() {
        GL11Interceptor.glBindTexture(0x0DE1, 42);
        assertEquals(42, GL11Interceptor.currentTextureId());
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*GL11InterceptorTest'`
- Expected output:
  ```
  FAILED ... GL11Interceptor cannot be resolved
  ```

**Step 3: 实现最小拦截层**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/intercept/GL11Interceptor.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.intercept;

import github.kasuminova.ssoptimizer.render.batch.NativeCommandBuffer;
import github.kasuminova.ssoptimizer.render.batch.RenderCommand;

public final class GL11Interceptor {
    private static NativeCommandBuffer buffer;
    private static final ThreadLocal<CommandState> STATE = ThreadLocal.withInitial(CommandState::new);

    private GL11Interceptor() {
    }

    public static void init(int maxCommands) {
        buffer = new NativeCommandBuffer(maxCommands);
    }

    public static void shutdown() {
        if (buffer != null) {
            buffer.destroy();
            buffer = null;
        }
        STATE.remove();
    }

    public static void glBegin(int mode) {
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
        CommandState state = STATE.get();
        state.accumulating = false;
        if (buffer != null && state.current != null) {
            buffer.append(state.current);
        }
        state.current = null;
    }

    public static void glVertex2f(float x, float y) {
        CommandState state = STATE.get();
        if (state.current != null && state.vertexIndex < 4) {
            state.current.setVertex(state.vertexIndex, x, y, 0.0f, 0.0f);
            state.vertexIndex++;
        }
    }

    public static void glTexCoord2f(float u, float v) {
        CommandState state = STATE.get();
        if (state.current != null && state.vertexIndex < 4) {
            state.current.u[state.vertexIndex] = u;
            state.current.v[state.vertexIndex] = v;
        }
    }

    public static void glBindTexture(int target, int textureId) {
        STATE.get().textureId = textureId;
    }

    public static void glBlendFunc(int src, int dst) {
        CommandState state = STATE.get();
        state.blendSrc = src;
        state.blendDst = dst;
    }

    public static void glColor4f(float r, float g, float b, float a) {
        CommandState state = STATE.get();
        state.r = r;
        state.g = g;
        state.b = b;
        state.a = a;
    }

    public static boolean isAccumulating() {
        return STATE.get().accumulating;
    }

    public static int pendingCount() {
        return buffer == null ? 0 : buffer.count();
    }

    public static int currentTextureId() {
        return STATE.get().textureId;
    }

    private static final class CommandState {
        boolean accumulating;
        int textureId = -1;
        int blendSrc;
        int blendDst;
        float r = 1.0f;
        float g = 1.0f;
        float b = 1.0f;
        float a = 1.0f;
        int vertexIndex;
        RenderCommand current;
    }
}
```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*GL11InterceptorTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 6: 实现 ASM 改写器 `GL11RewriteProcessor`

**Step 1: 写失败测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/intercept/GL11RewriteProcessorTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.intercept;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GL11RewriteProcessorTest {
    private byte[] createFakeClassWithGL11Call() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/FakeRenderer", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "draw", "()V", null, null);
        mv.visitCode();
        mv.visitIntInsn(Opcodes.SIPUSH, 0x0007);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glBegin", "(I)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void rewritesGL11CallToInterceptor() {
        byte[] rewritten = new GL11RewriteProcessor().process(createFakeClassWithGL11Call());
        assertNotNull(rewritten);

        ClassReader reader = new ClassReader(rewritten);
        final boolean[] found = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (owner.contains("GL11Interceptor") && name.equals("glBegin")) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, 0);
        assertTrue(found[0]);
    }

    @Test
    void returnsNullWhenNoGL11CallsExist() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Safe", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();

        assertNull(new GL11RewriteProcessor().process(cw.toByteArray()));
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*GL11RewriteProcessorTest'`
- Expected output:
  ```
  FAILED ... GL11RewriteProcessor cannot be resolved
  ```

**Step 3: 实现最小 ASM 重写器**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/intercept/GL11RewriteProcessor.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.intercept;

import github.kasuminova.ssoptimizer.agent.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Set;

public final class GL11RewriteProcessor implements AsmClassProcessor {
    private static final String GL11_OWNER = "org/lwjgl/opengl/GL11";
    private static final String INTERCEPTOR_OWNER = "github/kasuminova/ssoptimizer/render/intercept/GL11Interceptor";
    private static final Set<String> INTERCEPTED_METHODS = Set.of(
            "glBegin",
            "glEnd",
            "glVertex2f",
            "glTexCoord2f",
            "glBindTexture",
            "glBlendFunc",
            "glColor4f"
    );

    @Override
    public byte[] process(byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        final boolean[] modified = {false};

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, sig, ex);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (owner.equals(GL11_OWNER) && INTERCEPTED_METHODS.contains(methodName)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, INTERCEPTOR_OWNER, methodName, methodDesc, false);
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
- Command: `./gradlew :app:test --tests '*GL11RewriteProcessorTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 7: 实现 Mod 兼容白名单 `ModWhitelist`

**Step 1: 写失败测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/intercept/ModWhitelistTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.intercept;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModWhitelistTest {
    @Test
    void emptyWhitelistDoesNotExcludeAnything() {
        ModWhitelist whitelist = new ModWhitelist();
        assertFalse(whitelist.isExcluded("com/example/SomeMod"));
    }

    @Test
    void excludesMatchingPackagePrefix() {
        ModWhitelist whitelist = new ModWhitelist();
        whitelist.addExclusion("com.problematic.mod");
        assertTrue(whitelist.isExcluded("com/problematic/mod/Renderer"));
        assertFalse(whitelist.isExcluded("com/safe/mod/Renderer"));
    }

    @Test
    void removeExclusionWorks() {
        ModWhitelist whitelist = new ModWhitelist();
        whitelist.addExclusion("com.temp.mod");
        whitelist.removeExclusion("com.temp.mod");
        assertFalse(whitelist.isExcluded("com/temp/mod/Foo"));
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*ModWhitelistTest'`
- Expected output:
  ```
  FAILED ... ModWhitelist cannot be resolved
  ```

**Step 3: 实现最小白名单**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/intercept/ModWhitelist.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.intercept;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public final class ModWhitelist {
    private final Set<String> exclusions = new CopyOnWriteArraySet<>();

    public void addExclusion(String packageOrClass) {
        exclusions.add(packageOrClass.replace('.', '/'));
    }

    public void removeExclusion(String packageOrClass) {
        exclusions.remove(packageOrClass.replace('.', '/'));
    }

    public boolean isExcluded(String internalClassName) {
        for (String exclusion : exclusions) {
            if (internalClassName.equals(exclusion) || internalClassName.startsWith(exclusion + "/")) {
                return true;
            }
        }
        return false;
    }

    public Set<String> getExclusions() {
        return Set.copyOf(exclusions);
    }
}
```

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*ModWhitelistTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 8: 将白名单接入 ASM 重写器

**Step 1: 写失败测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/intercept/GL11RewriteWithWhitelistTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render.intercept;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GL11RewriteWithWhitelistTest {
    private byte[] createClassWithGL11(String className) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "draw", "()V", null, null);
        mv.visitCode();
        mv.visitIntInsn(Opcodes.SIPUSH, 0x0007);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glBegin", "(I)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void whitelistedClassIsNotRewritten() {
        ModWhitelist whitelist = new ModWhitelist();
        whitelist.addExclusion("com.excluded.mod");
        GL11RewriteProcessor processor = new GL11RewriteProcessor(whitelist);

        assertNull(processor.process(createClassWithGL11("com/excluded/mod/Renderer")));
    }

    @Test
    void nonWhitelistedClassIsRewritten() {
        ModWhitelist whitelist = new ModWhitelist();
        whitelist.addExclusion("com.excluded.mod");
        GL11RewriteProcessor processor = new GL11RewriteProcessor(whitelist);

        assertNotNull(processor.process(createClassWithGL11("com/normal/mod/Renderer")));
    }
}
```

**Step 2: 运行测试并确认失败**
- Command: `./gradlew :app:test --tests '*GL11RewriteWithWhitelistTest'`
- Expected output:
  ```
  FAILED ... GL11RewriteProcessor does not accept a whitelist yet
  ```

**Step 3: 扩展 `GL11RewriteProcessor` 支持白名单**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/render/intercept/GL11RewriteProcessor.java`
- 修改内容：
  - 增加接收 `ModWhitelist` 的构造函数。
  - 在 `process()` 入口处根据类名判断是否在白名单中；若命中白名单，直接返回 `null`。
  - 保留无参构造函数作为默认“全局接管”模式。

**Step 4: 重新运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*GL11RewriteWithWhitelistTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 9: 将 ASM 处理器注册到 Agent

**Step 1: 写失败测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/agent/GL11RegistrationTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.agent;

import github.kasuminova.ssoptimizer.render.intercept.GL11RewriteProcessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GL11RegistrationTest {
    @Test
    void weaverCanRegisterGL11Processor() {
        HybridWeaverTransformer transformer = new HybridWeaverTransformer();
        transformer.registerProcessor("org.lwjgl.opengl.GL11", new GL11RewriteProcessor());
        assertEquals(1, transformer.getProcessorCount());
    }
}
```

**Step 2: 运行测试并确认通过或暴露注册问题**
- Command: `./gradlew :app:test --tests '*GL11RegistrationTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

**Step 3: 如需修正，更新 `SSOptimizerAgent` 的 premain 注册逻辑**
- File: `app/src/main/java/github/kasuminova/ssoptimizer/agent/SSOptimizerAgent.java`
- 在 `premain()` 中注册 `GL11RewriteProcessor`，并在可用时加载白名单配置。

**Step 4: 再次运行相关测试**
- Command: `./gradlew :app:test --tests '*SSOptimizerAgentTest' --tests '*GL11RegistrationTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 10: 将 Phase A/B 适配层与批处理路径对接

**Step 1: 写失败测试**
- File: `app/src/test/java/github/kasuminova/ssoptimizer/render/SpriteRenderAdapterBatchTest.java`
- Code:
```java
package github.kasuminova.ssoptimizer.render;

import github.kasuminova.ssoptimizer.render.intercept.GL11Interceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpriteRenderAdapterBatchTest {
    @BeforeEach
    void setUp() {
        GL11Interceptor.init(256);
    }

    @AfterEach
    void tearDown() {
        GL11Interceptor.shutdown();
        RenderStateCache.clear();
    }

    @Test
    void adapterPathCanProducePendingCommand() {
        GL11Interceptor.glBindTexture(0x0DE1, 99);
        GL11Interceptor.glBegin(0x0007);
        GL11Interceptor.glVertex2f(0, 0);
        GL11Interceptor.glVertex2f(1, 0);
        GL11Interceptor.glVertex2f(1, 1);
        GL11Interceptor.glVertex2f(0, 1);
        GL11Interceptor.glEnd();

        assertEquals(1, GL11Interceptor.pendingCount());
        assertEquals(99, GL11Interceptor.currentTextureId());
    }
}
```

**Step 2: 运行测试并确认通过**
- Command: `./gradlew :app:test --tests '*SpriteRenderAdapterBatchTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

**Step 3: 如需补齐 Mixin 配置，更新 `mixins.ssoptimizer.json`**
- File: `app/src/main/resources/mixins.ssoptimizer.json`
- 确保 render hooks 仍在 `client` 或 `mixins` 配置中声明。

**Step 4: 再次运行混入配置相关测试**
- Command: `./gradlew :app:test --tests '*RenderMixinConfigTest'`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

---

### Task 11: 全量测试与 JAR / Native 产物验证

**Step 1: 运行 Java 全量测试**
- Command: `./gradlew :app:test`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

**Step 2: 构建 native 模块**
- Command: `./gradlew :native:assemble`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

**Step 3: 打包 JAR**
- Command: `./gradlew :app:jar`
- Expected output:
  ```
  BUILD SUCCESSFUL
  ```

**Step 4: 检查最终产物**
- Command: `jar tf app/build/libs/SSOptimizer.jar | grep -E '(RenderCommand|NativeCommandBuffer|NativeRenderer|GL11Interceptor|GL11RewriteProcessor|ModWhitelist)\.class'`
- Expected output:
  ```
  github/kasuminova/ssoptimizer/render/batch/RenderCommand.class
  github/kasuminova/ssoptimizer/render/batch/NativeCommandBuffer.class
  github/kasuminova/ssoptimizer/render/batch/NativeRenderer.class
  github/kasuminova/ssoptimizer/render/intercept/GL11Interceptor.class
  github/kasuminova/ssoptimizer/render/intercept/GL11RewriteProcessor.class
  github/kasuminova/ssoptimizer/render/intercept/ModWhitelist.class
  ```

---

### 计划分层

| 层级 | 任务 | 内容 |
|------|------|------|
| 数据协议层 | Task 1-3 | `RenderCommand` → `NativeCommandBuffer` → `NativeRenderer` |
| 原生执行层 | Task 4 | JNI 结构体镜像与 C++ 入口 |
| 运行时接管层 | Task 5-8 | `GL11Interceptor`、`GL11RewriteProcessor`、`ModWhitelist` |
| 集成验证层 | Task 9-11 | Agent 注册、适配层对接、全量构建与打包验证 |

---

### 验收目标
- 命令队列可在 Java 侧稳定累积渲染请求。
- JNI 可将命令批次一次性下发给 native 层。
- ASM 可将目标 `GL11` 调用重写到拦截层。
- 白名单可绕过冲突模组，避免过度接管。
- 全量测试、native 构建与 JAR 打包均可通过。
