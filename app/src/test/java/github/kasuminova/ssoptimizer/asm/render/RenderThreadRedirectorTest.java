package github.kasuminova.ssoptimizer.asm.render;

import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RenderThreadRedirector} 的改写规则验证。
 * <p>
 * 用 ASM 现造调用点字节码（不加载真实类）：分别覆盖「镜像方法 owner 改写、
 * 未镜像方法保持原 owner、GLSync 描述符类型改写、Drawable INVOKEINTERFACE、
 * LDC 字符串字面量不动、bridge/LWJGL 包排除、flag 关闭 no-op、Janino 批量入口」。
 */
class RenderThreadRedirectorTest {
    private String previousFlag;

    @BeforeEach
    void setUp() {
        previousFlag = System.getProperty(RenderThreadMode.ENABLE_PROPERTY);
        System.setProperty(RenderThreadMode.ENABLE_PROPERTY, "true");
    }

    @AfterEach
    void tearDown() {
        if (previousFlag == null) {
            System.clearProperty(RenderThreadMode.ENABLE_PROPERTY);
        } else {
            System.setProperty(RenderThreadMode.ENABLE_PROPERTY, previousFlag);
        }
    }

    /** 记录全部方法调用指令的 visitor（断言用）。 */
    private static List<String> collectMethodCalls(byte[] classBytes) {
        List<String> calls = new ArrayList<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9, null) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, null) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean itf) {
                        calls.add(owner + '.' + methodName + methodDesc);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return calls;
    }

    /** 收集全部 LDC 常量（断言字符串字面量未被改动）。 */
    private static List<Object> collectLdc(byte[] classBytes) {
        List<Object> constants = new ArrayList<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9, null) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, null) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        constants.add(value);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return constants;
    }

    /**
     * 造一个只含 {@code run()V} 静态方法的类，方法体由 {@code body} 填充。
     */
    private static byte[] buildClass(String internalName, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void mirroredMethodOwnerIsRedirected() {
        byte[] source = buildClass("com/example/UsesGl", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glBegin", "(I)V", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glEnd", "()V", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/Display", "update", "()V", false);
        });

        byte[] result = RenderThreadRedirector.redirect("com.example.UsesGl", source);
        assertNotSame(source, result, "存在镜像调用点时字节码必须被改写");

        List<String> calls = collectMethodCalls(result);
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/GL11.glBegin(I)V"));
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/GL11.glEnd()V"));
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/Display.update()V"));
        assertFalse(calls.stream().anyMatch(c -> c.startsWith("org/lwjgl/")),
                "镜像调用点不得残留原 owner");
    }

    @Test
    void unmirroredMethodKeepsOriginalOwner() {
        // GL44.glBufferStorage 不在 bridge 镜像面内（BoxUtil 级高端面）
        byte[] source = buildClass("com/example/UsesGl44", mv ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL44",
                        "glBufferStorage", "(IJLjava/nio/ByteBuffer;I)V", false));

        byte[] result = RenderThreadRedirector.redirect("com.example.UsesGl44", source);
        assertSame(source, result, "未镜像调用保持原字节（无任何改写点）");
    }

    @Test
    void mixedMirroredAndUnmirroredCallsOnlyRewriteMirrored() {
        // GL11.glBegin 已镜像；GL11.glGetTexParameterf（假定未镜像）保持原样
        byte[] source = buildClass("com/example/Mixed", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glBegin", "(I)V", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11",
                    "glGetTexParameterf", "(II)F", false);
        });

        byte[] result = RenderThreadRedirector.redirect("com.example.Mixed", source);
        List<String> calls = collectMethodCalls(result);
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/GL11.glBegin(I)V"));
        assertTrue(calls.contains("org/lwjgl/opengl/GL11.glGetTexParameterf(II)F"),
                "未镜像的 GL11 方法必须保持原 owner");
    }

    @Test
    void glSyncDescriptorTypesAreRemappedAtCallSite() {
        // GL32.glFenceSync 返回 GLSync：owner 与描述符类型同步改写
        byte[] source = buildClass("com/example/UsesFence", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL32",
                    "glFenceSync", "(II)Lorg/lwjgl/opengl/GLSync;", false);
            mv.visitInsn(Opcodes.POP);
        });

        byte[] result = RenderThreadRedirector.redirect("com.example.UsesFence", source);
        List<String> calls = collectMethodCalls(result);
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/GL32.glFenceSync"
                + "(II)Lgithub/kasuminova/ssoptimizer/bridge/opengl/GLSync;"),
                "GLSync 返回类型必须改写为 bridge 类型，实际调用点: " + calls);
    }

    @Test
    void drawableInvokeInterfaceIsRedirected() {
        byte[] source = buildClass("com/example/UsesDrawable", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/lwjgl/opengl/Drawable",
                    "makeCurrent", "()V", true);
        });

        byte[] result = RenderThreadRedirector.redirect("com.example.UsesDrawable", source);
        List<String> calls = collectMethodCalls(result);
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/Drawable.makeCurrent()V"));
    }

    @Test
    void ldcStringLiteralsAreUntouched() {
        byte[] source = buildClass("com/example/UsesString", mv -> {
            mv.visitLdcInsn("org/lwjgl/opengl/GL11");
            mv.visitInsn(Opcodes.POP);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glFlush", "()V", false);
        });

        byte[] result = RenderThreadRedirector.redirect("com.example.UsesString", source);
        List<Object> ldc = collectLdc(result);
        assertTrue(ldc.contains("org/lwjgl/opengl/GL11"), "字符串字面量不得被改写");
    }

    @Test
    void bridgePackageIsExcluded() {
        byte[] source = buildClass("github/kasuminova/ssoptimizer/bridge/opengl/FakeCmd", mv ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glBegin", "(I)V", false));
        assertSame(source, RenderThreadRedirector.redirect(
                "github.kasuminova.ssoptimizer.bridge.opengl.FakeCmd", source),
                "bridge 包自身绝不改写");
    }

    @Test
    void lwjglInternalsAreExcluded() {
        byte[] source = buildClass("org/lwjgl/opengl/LinuxDisplay", mv ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glBegin", "(I)V", false));
        assertSame(source, RenderThreadRedirector.redirect("org.lwjgl.opengl.LinuxDisplay", source),
                "LWJGL 内部类绝不改写");
    }

    @Test
    void classWithoutLwjglReferencePassesThroughByPrefilter() {
        byte[] source = buildClass("com/example/NoGl", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.POP);
        });
        assertSame(source, RenderThreadRedirector.redirect("com.example.NoGl", source));
    }

    @Test
    void disabledFlagIsCompleteNoOp() {
        System.setProperty(RenderThreadMode.ENABLE_PROPERTY, "false");
        byte[] source = buildClass("com/example/UsesGl", mv ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glBegin", "(I)V", false));
        assertSame(source, RenderThreadRedirector.redirect("com.example.UsesGl", source),
                "flag 关闭时必须原样返回");
    }

    @Test
    void janinoBatchEntryRewritesEachEntry() {
        byte[] gl = buildClass("data/scripts/Foo", mv ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glBegin", "(I)V", false));
        byte[] plain = buildClass("data/scripts/Bar", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.POP);
        });

        Map<String, byte[]> result = RenderThreadRedirector.redirectAll(
                Map.of("data.scripts.Foo", gl, "data.scripts.Bar", plain));
        assertNotSame(gl, result.get("data.scripts.Foo"));
        assertSame(plain, result.get("data.scripts.Bar"));
    }

    @Test
    void gl33CallsAreRedirected() {
        // GL33 曾整体缺席镜像表：Particle Engine 的 ParticleAllocator 调
        // glVertexAttribDivisor 直奔真实 GL（No OpenGL context 崩溃签名）。
        // 抽查实例化除数 + sampler 族 + 时间戳查询必须全部改写。
        byte[] source = buildClass("com/example/UsesGl33", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL33",
                    "glVertexAttribDivisor", "(II)V", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL33",
                    "glGenSamplers", "()I", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL33",
                    "glQueryCounter", "(II)V", false);
        });

        byte[] result = RenderThreadRedirector.redirect("com.example.UsesGl33", source);
        List<String> calls = collectMethodCalls(result);
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/GL33.glVertexAttribDivisor(II)V"), calls.toString());
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/GL33.glGenSamplers()I"), calls.toString());
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/GL33.glQueryCounter(II)V"), calls.toString());
        assertFalse(calls.stream().anyMatch(c -> c.contains("org/lwjgl/opengl/GL33")),
                "GL33 调用点不得残留原 owner: " + calls);
    }

    @Test
    void arbInstancedVariantsAreRedirected() {
        // GL33/GL31 实例化入口的 ARB 扩展别名：模组按能力探测二选一，同语义必须同覆盖
        byte[] source = buildClass("com/example/UsesArbInstanced", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/ARBInstancedArrays",
                    "glVertexAttribDivisorARB", "(II)V", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/ARBDrawInstanced",
                    "glDrawArraysInstancedARB", "(IIII)V", false);
        });

        byte[] result = RenderThreadRedirector.redirect("com.example.UsesArbInstanced", source);
        List<String> calls = collectMethodCalls(result);
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/ARBInstancedArrays.glVertexAttribDivisorARB(II)V"), calls.toString());
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/ARBDrawInstanced.glDrawArraysInstancedARB(IIII)V"), calls.toString());
    }

    @Test
    void mirrorTableCoversExpectedSurface() {
        // 镜像表是改写覆盖面的事实源：抽查关键方法必须已镜像（防 bridge 重构漂移）
        byte[] source = buildClass("com/example/Probe", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glGenTextures", "()I", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glNewList", "(II)V", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/Display",
                    "create", "(Lorg/lwjgl/opengl/PixelFormat;)V", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/Display", "update", "(Z)V", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GLContext",
                    "getCapabilities", "()Lorg/lwjgl/opengl/ContextCapabilities;", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ARETURN);
        });
        // 上面的 run()V 与 ARETURN 不匹配，仅为收集调用点，不做校验执行
        List<String> calls = collectMethodCalls(RenderThreadRedirector.redirect("com.example.Probe", source));
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/GL11.glGenTextures()I"), calls.toString());
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/GL11.glNewList(II)V"), calls.toString());
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/Display.create(Lorg/lwjgl/opengl/PixelFormat;)V"), calls.toString());
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/Display.update(Z)V"), calls.toString());
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/GLContext.getCapabilities()Lorg/lwjgl/opengl/ContextCapabilities;"), calls.toString());
    }
}
