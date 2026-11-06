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
    void glClearDepthIsMirrored() {
        byte[] source = buildClass("org/boxutil/define/GLWrapper$FBO", mv -> {
            mv.visitVarInsn(Opcodes.DLOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11",
                    "glClearDepth", "(D)V", false);
        });

        byte[] result = RenderThreadRedirector.redirect("org.boxutil.define.GLWrapper$FBO", source);
        assertNotSame(source, result, "glClearDepth 调用点必须被改写");

        List<String> calls = collectMethodCalls(result);
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/GL11.glClearDepth(D)V"));
        assertFalse(calls.contains("org/lwjgl/opengl/GL11.glClearDepth(D)V"),
                "glClearDepth 不得残留原 owner");
    }

    @Test
    void boxUtil160FacadeSignaturesAreMirrored() {
        final String[][] signatures = {
                {"org/lwjgl/opengl/GL11", "glDepthRange", "(DD)V"},
                {"org/lwjgl/opengl/GL11", "glTexParameter", "(IILjava/nio/IntBuffer;)V"},
                {"org/lwjgl/opengl/GL11", "glTexImage1D", "(IIIIIIIJ)V"},
                {"org/lwjgl/opengl/GL11", "glTexImage2D", "(IIIIIIIIJ)V"},
                {"org/lwjgl/opengl/GL11", "glTexSubImage1D", "(IIIIIIJ)V"},
                {"org/lwjgl/opengl/GL11", "glTexSubImage2D", "(IIIIIIIIJ)V"},
                {"org/lwjgl/opengl/GL12", "glTexImage3D", "(IIIIIIIIIJ)V"},
                {"org/lwjgl/opengl/GL12", "glTexImage3D", "(IIIIIIIIILjava/nio/DoubleBuffer;)V"},
                {"org/lwjgl/opengl/GL12", "glTexImage3D", "(IIIIIIIIILjava/nio/FloatBuffer;)V"},
                {"org/lwjgl/opengl/GL12", "glTexImage3D", "(IIIIIIIIILjava/nio/IntBuffer;)V"},
                {"org/lwjgl/opengl/GL12", "glTexImage3D", "(IIIIIIIIILjava/nio/ShortBuffer;)V"},
                {"org/lwjgl/opengl/GL12", "glTexSubImage3D", "(IIIIIIIIIIJ)V"},
                {"org/lwjgl/opengl/GL12", "glTexSubImage3D", "(IIIIIIIIIILjava/nio/ByteBuffer;)V"},
                {"org/lwjgl/opengl/GL12", "glTexSubImage3D", "(IIIIIIIIIILjava/nio/DoubleBuffer;)V"},
                {"org/lwjgl/opengl/GL12", "glTexSubImage3D", "(IIIIIIIIIILjava/nio/IntBuffer;)V"},
                {"org/lwjgl/opengl/GL12", "glTexSubImage3D", "(IIIIIIIIIILjava/nio/ShortBuffer;)V"},
                {"org/lwjgl/opengl/GL32", "glGetInteger64", "(ILjava/nio/LongBuffer;)V"},
                {"org/lwjgl/opengl/GL32", "glGetInteger64", "(IILjava/nio/LongBuffer;)V"},
                {"org/lwjgl/opengl/ARBSync", "glGetInteger64", "(I)J"},
                {"org/lwjgl/opengl/ARBSync", "glGetInteger64", "(ILjava/nio/LongBuffer;)V"},
        };
        for (final String[] sig : signatures) {
            final byte[] source = buildClass("org/boxutil/define/GLWrapper$Audit", mv ->
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, sig[0], sig[1], sig[2], false));

            final byte[] result = RenderThreadRedirector.redirect("org.boxutil.define.GLWrapper$Audit", source);
            final String expected = "github/kasuminova/ssoptimizer/bridge/opengl/"
                    + sig[0].substring(sig[0].lastIndexOf('/') + 1) + '.' + sig[1] + sig[2];
            assertNotSame(source, result, "调用点必须被改写: " + sig[0] + '.' + sig[1] + sig[2]);
            assertTrue(collectMethodCalls(result).contains(expected),
                    "镜像目标缺失: " + expected);
        }
    }

    @Test
    void unmirroredMethodKeepsOriginalOwner() {
        byte[] source = buildClass("com/example/UsesGl44", mv ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL44",
                        "glFutureOp", "(IJLjava/nio/ByteBuffer;I)V", false));

        byte[] result = RenderThreadRedirector.redirect("com.example.UsesGl44", source);
        assertSame(source, result, "未镜像调用保持原字节（无任何改写点）");
    }

    @Test
    void mixedMirroredAndUnmirroredCallsOnlyRewriteMirrored() {
        byte[] source = buildClass("com/example/Mixed", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glBegin", "(I)V", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11",
                    "glFutureOp", "(II)F", false);
        });

        byte[] result = RenderThreadRedirector.redirect("com.example.Mixed", source);
        List<String> calls = collectMethodCalls(result);
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/GL11.glBegin(I)V"));
        assertTrue(calls.contains("org/lwjgl/opengl/GL11.glFutureOp(II)F"),
                "未镜像的 GL11 方法必须保持原 owner");
    }

    @Test
    void glSyncDescriptorTypesAreRemappedAtCallSite() {
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
    void fontAtlasGlIsExcludedFromRedirect() {
        // FontAtlasGl 在渲染线程直接执行 GL，不能被重定向为延迟录制调用。
        byte[] source = buildClass("github/kasuminova/ssoptimizer/bridge/opengl/FontAtlasGl", mv ->
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11",
                        "glBindTexture", "(II)V", false));
        assertSame(source, RenderThreadRedirector.redirect(
                        "github.kasuminova.ssoptimizer.bridge.opengl.FontAtlasGl", source),
                "FontAtlasGl 命中 bridge 包排除规则，绝不改写");
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
        List<String> calls = collectMethodCalls(RenderThreadRedirector.redirect("com.example.Probe", source));
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/GL11.glGenTextures()I"), calls.toString());
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/GL11.glNewList(II)V"), calls.toString());
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/Display.create(Lorg/lwjgl/opengl/PixelFormat;)V"), calls.toString());
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/Display.update(Z)V"), calls.toString());
        assertTrue(calls.contains("github/kasuminova/ssoptimizer/bridge/opengl/GLContext.getCapabilities()Lorg/lwjgl/opengl/ContextCapabilities;"), calls.toString());
    }

    @Test
    void gluSphereDrawIsRedirectedToGluSupport() {
        byte[] source = buildClass("com/example/UsesGluSphere", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.FCONST_1);
            mv.visitIntInsn(Opcodes.BIPUSH, 16);
            mv.visitIntInsn(Opcodes.BIPUSH, 8);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/lwjgl/util/glu/Sphere",
                    "draw", "(FII)V", false);
        });

        byte[] result = RenderThreadRedirector.redirect("com.example.UsesGluSphere", source);
        assertNotSame(source, result, "Sphere.draw 调用点必须被改写（含仅引 Sphere 的预筛放行）");

        List<String> opcodes = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        new ClassReader(result).accept(new ClassVisitor(Opcodes.ASM9, null) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, null) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean itf) {
                        opcodes.add(String.valueOf(opcode));
                        calls.add(owner + '.' + methodName + methodDesc);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        int idx = calls.indexOf("github/kasuminova/ssoptimizer/bridge/opengl/GluSupport"
                + ".enqueueSphereDraw(Lorg/lwjgl/util/glu/Sphere;FII)V");
        assertTrue(idx >= 0, "必须改写为 GluSupport.enqueueSphereDraw，实际: " + calls);
        assertEquals(String.valueOf(Opcodes.INVOKESTATIC), opcodes.get(idx),
                "receiver 变首参后必须是 INVOKESTATIC（栈形状不变）");
        assertFalse(calls.stream().anyMatch(c -> c.startsWith("org/lwjgl/util/glu/")),
                "Sphere.draw 原调用点不得残留: " + calls);
    }

    @Test
    void gluSphereConfigMethodsAreUntouched() {
        byte[] source = buildClass("com/example/ConfiguresGluSphere", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitIntInsn(Opcodes.BIPUSH, 100);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/lwjgl/util/glu/Sphere",
                    "setDrawStyle", "(I)V", false);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/lwjgl/util/glu/Sphere",
                    "setTextureFlag", "(Z)V", false);
        });

        byte[] result = RenderThreadRedirector.redirect("com.example.ConfiguresGluSphere", source);
        assertSame(source, result, "Sphere 纯配置方法不得改写");
    }

    private static List<String> collectIndyHandles(byte[] classBytes) {
        List<String> handles = new ArrayList<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9, null) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, null) {
                    @Override
                    public void visitInvokeDynamicInsn(String indyName, String indyDesc,
                                                       org.objectweb.asm.Handle bsm, Object... bsmArgs) {
                        for (Object arg : bsmArgs) {
                            if (arg instanceof org.objectweb.asm.Handle handle
                                    && handle.getOwner().startsWith("org/lwjgl/")) {
                                handles.add(handle.getOwner() + '.' + handle.getName() + handle.getDesc());
                            } else if (arg instanceof org.objectweb.asm.Handle handle
                                    && handle.getOwner().startsWith("github/kasuminova/")) {
                                handles.add(handle.getOwner() + '.' + handle.getName() + handle.getDesc());
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return handles;
    }

    private static void visitLambdaRef(MethodVisitor mv, String owner, String name, String desc) {
        org.objectweb.asm.Handle metafactory = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        mv.visitInvokeDynamicInsn("run", "()Lcom/example/Fun;", metafactory,
                org.objectweb.asm.Type.getType("()V"),
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, owner, name, desc, false),
                org.objectweb.asm.Type.getType("()V"));
        mv.visitInsn(Opcodes.POP);
    }

    @Test
    void indyHandlesFollowMirrorWhitelist() {
        byte[] source = buildClass("com/example/LambdaRefs", mv -> {
            visitLambdaRef(mv, "org/lwjgl/opengl/GL11", "glDrawArrays", "(III)V");
            visitLambdaRef(mv, "org/lwjgl/opengl/GL12", "glDrawRangeElements", "(IIIIIJ)V");
            visitLambdaRef(mv, "org/lwjgl/opengl/GL11", "glFutureOp", "(II)F");
        });

        byte[] result = RenderThreadRedirector.redirect("com.example.LambdaRefs", source);
        List<String> handles = collectIndyHandles(result);
        assertTrue(handles.contains(
                "github/kasuminova/ssoptimizer/bridge/opengl/GL11.glDrawArrays(III)V"),
                "已镜像句柄必须改写到桥: " + handles);
        assertTrue(handles.contains(
                "github/kasuminova/ssoptimizer/bridge/opengl/GL12.glDrawRangeElements(IIIIIJ)V"),
                "补齐覆盖后的崩溃现场 API 必须改写到桥: " + handles);
        assertTrue(handles.contains("org/lwjgl/opengl/GL11.glFutureOp(II)F"),
                "未镜像句柄（假想签名）必须保持原 owner（链接真实 LWJGL 方法）: " + handles);
    }

    @Test
    void indyHandleToUnmirroredMethodAloneIsNoOp() {
        byte[] source = buildClass("com/example/LambdaUnmirrored", mv ->
                visitLambdaRef(mv, "org/lwjgl/opengl/GL11", "glFutureOp", "(II)F"));

        byte[] result = RenderThreadRedirector.redirect("com.example.LambdaUnmirrored", source);
        assertSame(source, result, "未镜像方法句柄不得改写（无改写点时原样返回）");
    }

    @Test
    void indyInstantiatedMethodTypeWithIdentityTypeIsRemapped() {
        byte[] source = buildClass("com/example/LambdaSyncRef", mv -> {
            org.objectweb.asm.Handle metafactory = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC,
                    "java/lang/invoke/LambdaMetafactory", "metafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                            + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                            + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                            + "Ljava/lang/invoke/CallSite;", false);
            mv.visitInvokeDynamicInsn("run", "()Lcom/example/XIntLongFunInt;", metafactory,
                    org.objectweb.asm.Type.getType("(Ljava/lang/Object;IJ)I"),
                    new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "org/lwjgl/opengl/GL32",
                            "glClientWaitSync", "(Lorg/lwjgl/opengl/GLSync;IJ)I", false),
                    org.objectweb.asm.Type.getType("(Lorg/lwjgl/opengl/GLSync;IJ)I"));
            mv.visitInsn(Opcodes.POP);
        });

        byte[] result = RenderThreadRedirector.redirect("com.example.LambdaSyncRef", source);
        List<String> types = new ArrayList<>();
        List<String> handles = new ArrayList<>();
        new ClassReader(result).accept(new ClassVisitor(Opcodes.ASM9, null) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, null) {
                    @Override
                    public void visitInvokeDynamicInsn(String indyName, String indyDesc,
                                                       org.objectweb.asm.Handle bsm, Object... bsmArgs) {
                        for (Object arg : bsmArgs) {
                            if (arg instanceof org.objectweb.asm.Type type) {
                                types.add(type.getDescriptor());
                            } else if (arg instanceof org.objectweb.asm.Handle handle) {
                                handles.add(handle.getOwner() + '.' + handle.getName() + handle.getDesc());
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        assertTrue(handles.contains("github/kasuminova/ssoptimizer/bridge/opengl/GL32"
                        + ".glClientWaitSync(Lgithub/kasuminova/ssoptimizer/bridge/opengl/GLSync;IJ)I"),
                "impl Handle 必须改写到桥（含描述符身份类型替换）: " + handles);
        assertTrue(types.contains("(Lgithub/kasuminova/ssoptimizer/bridge/opengl/GLSync;IJ)I"),
                "instantiatedMethodType 的身份类型必须同步替换: " + types);
        assertTrue(types.contains("(Ljava/lang/Object;IJ)I"), "sam 泛型擦除形态不受影响: " + types);
        assertFalse(types.stream().anyMatch(t -> t.contains("org/lwjgl/opengl/GLSync")),
                "indy 参数不得残留 lwjgl GLSync: " + types);
    }

    private static void collectIndyArgs(byte[] classBytes, List<String> types, List<String> handles) {
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9, null) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, null) {
                    @Override
                    public void visitInvokeDynamicInsn(String indyName, String indyDesc,
                                                       org.objectweb.asm.Handle bsm, Object... bsmArgs) {
                        for (Object arg : bsmArgs) {
                            if (arg instanceof org.objectweb.asm.Type type) {
                                types.add(type.getDescriptor());
                            } else if (arg instanceof org.objectweb.asm.Handle handle) {
                                handles.add(handle.getOwner() + '.' + handle.getName() + handle.getDesc());
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static void visitSyncLambdaRef(MethodVisitor mv, String owner, String methodName) {
        org.objectweb.asm.Handle metafactory = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        mv.visitInvokeDynamicInsn("run", "()Lcom/example/XObjFun;", metafactory,
                org.objectweb.asm.Type.getType("(Ljava/lang/Object;)V"),
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, owner, methodName,
                        "(Lorg/lwjgl/opengl/GLSync;)V", false),
                org.objectweb.asm.Type.getType("(Lorg/lwjgl/opengl/GLSync;)V"));
        mv.visitInsn(Opcodes.POP);
    }

    @Test
    void indySiteWithUnmirroredIdentityHandleKeepsTypeArgs() {
        byte[] source = buildClass("com/example/LambdaUnmirroredSync", mv ->
                visitSyncLambdaRef(mv, "org/lwjgl/opengl/GL32", "glFutureSyncOp"));

        byte[] result = RenderThreadRedirector.redirect("com.example.LambdaUnmirroredSync", source);
        List<String> types = new ArrayList<>();
        List<String> handles = new ArrayList<>();
        collectIndyArgs(result, types, handles);
        assertTrue(handles.contains("org/lwjgl/opengl/GL32.glFutureSyncOp(Lorg/lwjgl/opengl/GLSync;)V"),
                "未镜像句柄必须保持原 owner: " + handles);
        assertTrue(types.contains("(Lorg/lwjgl/opengl/GLSync;)V"),
                "未镜像句柄站点的 MethodType 参数必须保持 lwjgl 原样: " + types);
        assertFalse(types.stream().anyMatch(t -> t.contains("bridge/opengl/GLSync")),
                "整站保留时不得出现桥身份类型: " + types);
    }

    @Test
    void indySiteWithModOwnedIdentityHandleIsRemapped() {
        byte[] source = buildClass("com/example/LambdaModOwnedSync", mv ->
                visitSyncLambdaRef(mv, "com/example/SyncHelper", "lambda$sync$0"));

        byte[] result = RenderThreadRedirector.redirect("com.example.LambdaModOwnedSync", source);
        List<String> types = new ArrayList<>();
        List<String> handles = new ArrayList<>();
        collectIndyArgs(result, types, handles);
        assertTrue(handles.contains("com/example/SyncHelper.lambda$sync$0"
                        + "(Lgithub/kasuminova/ssoptimizer/bridge/opengl/GLSync;)V"),
                "模组自有句柄描述符的身份类型必须同步改写（其声明同被改写）: " + handles);
        assertTrue(types.contains("(Lgithub/kasuminova/ssoptimizer/bridge/opengl/GLSync;)V"),
                "MethodType 参数的身份类型必须同步改写: " + types);
    }

    @Test
    void arbSyncFamilyHandlesAreMirrored() {
        byte[] source = buildClass("com/example/LambdaArbSync", mv -> {
            visitSyncLambdaRef(mv, "org/lwjgl/opengl/ARBSync", "glDeleteSync");
            org.objectweb.asm.Handle metafactory = new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC,
                    "java/lang/invoke/LambdaMetafactory", "metafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                            + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                            + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                            + "Ljava/lang/invoke/CallSite;", false);
            mv.visitInvokeDynamicInsn("run", "()Lcom/example/XIntFunInt;", metafactory,
                    org.objectweb.asm.Type.getType("(Ljava/lang/Object;I)I"),
                    new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "org/lwjgl/opengl/ARBSync",
                            "glGetSynci", "(Lorg/lwjgl/opengl/GLSync;I)I", false),
                    org.objectweb.asm.Type.getType("(Lorg/lwjgl/opengl/GLSync;I)I"));
            mv.visitInsn(Opcodes.POP);
        });

        byte[] result = RenderThreadRedirector.redirect("com.example.LambdaArbSync", source);
        List<String> types = new ArrayList<>();
        List<String> handles = new ArrayList<>();
        collectIndyArgs(result, types, handles);
        String bridge = "github/kasuminova/ssoptimizer/bridge/opengl/";
        assertTrue(handles.contains(bridge + "ARBSync.glDeleteSync(L" + bridge + "GLSync;)V"),
                "ARBSync 句柄必须改写到桥: " + handles);
        assertTrue(handles.contains(bridge + "ARBSync.glGetSynci(L" + bridge + "GLSync;I)I"),
                "ARBSync 句柄必须改写到桥: " + handles);
        assertTrue(types.contains("(L" + bridge + "GLSync;I)I"),
                "已镜像站点的 MethodType 身份类型必须同步改写: " + types);
    }

    private static List<String> collectDeclarations(byte[] classBytes) {
        List<String> declarations = new ArrayList<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9, null) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String desc,
                                                             String signature, Object value) {
                declarations.add("field " + name + ' ' + desc);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                declarations.add("method " + name + desc);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return declarations;
    }

    @Test
    void methodDeclarationWithIdentityTypeIsRemapped() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/SyncFacade", null,
                "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "glFenceSync", "(II)Lorg/lwjgl/opengl/GLSync;", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL32",
                "glFenceSync", "(II)Lorg/lwjgl/opengl/GLSync;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();

        byte[] result = RenderThreadRedirector.redirect("com.example.SyncFacade", writer.toByteArray());
        List<String> declarations = collectDeclarations(result);
        assertTrue(declarations.contains("method glFenceSync(II)"
                        + "Lgithub/kasuminova/ssoptimizer/bridge/opengl/GLSync;"),
                "方法声明的 GLSync 返回类型必须改写为 bridge: " + declarations);
        assertFalse(declarations.stream().anyMatch(d -> d.contains("org/lwjgl/opengl/GLSync")),
                "声明中不得残留 lwjgl GLSync: " + declarations);
    }

    @Test
    void fieldDeclarationAndAccessSiteAreRemappedTogether() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/SyncHolder", null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sync",
                "Lorg/lwjgl/opengl/GLSync;", null, null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, "com/example/SyncHolder", "sync",
                "Lorg/lwjgl/opengl/GLSync;");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();

        byte[] result = RenderThreadRedirector.redirect("com.example.SyncHolder", writer.toByteArray());
        List<String> declarations = collectDeclarations(result);
        assertTrue(declarations.contains("field sync "
                        + "Lgithub/kasuminova/ssoptimizer/bridge/opengl/GLSync;"),
                "字段声明的身份类型必须改写为 bridge: " + declarations);
        List<String> accesses = new ArrayList<>();
        new ClassReader(result).accept(new ClassVisitor(Opcodes.ASM9, null) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, null) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName,
                                               String fieldDesc) {
                        accesses.add(owner + '.' + fieldName + ' ' + fieldDesc);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        assertTrue(accesses.contains("com/example/SyncHolder.sync "
                        + "Lgithub/kasuminova/ssoptimizer/bridge/opengl/GLSync;"),
                "字段访问点的身份类型必须跟随声明改写: " + accesses);
    }

    @Test
    void modInternalCallSiteDescriptorIsRemapped() {
        byte[] source = buildClass("com/example/SyncCaller", mv -> {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/boxutil/define/GLWrapper$Operation$Sync",
                    "glFenceSync", "(II)Lorg/lwjgl/opengl/GLSync;", false);
            mv.visitInsn(Opcodes.POP);
        });

        byte[] result = RenderThreadRedirector.redirect("com.example.SyncCaller", source);
        List<String> calls = collectMethodCalls(result);
        assertTrue(calls.contains("org/boxutil/define/GLWrapper$Operation$Sync.glFenceSync"
                        + "(II)Lgithub/kasuminova/ssoptimizer/bridge/opengl/GLSync;"),
                "模组内部调用点的身份类型必须跟随声明改写: " + calls);
    }

    @Test
    void systemDomainOwnerDescriptorIsPreserved() {
        // System 域 owner 不经过改写器，其调用点描述符必须保留 lwjgl 类型。
        byte[] source = buildClass("com/example/ClInterop", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opencl/CLContext", "create",
                    "(Lorg/lwjgl/opencl/CLPlatform;Ljava/util/List;"
                            + "Lorg/lwjgl/opencl/CLContextCallback;Lorg/lwjgl/opengl/Drawable;"
                            + "Ljava/nio/IntBuffer;)Lorg/lwjgl/opencl/CLContext;", false);
            mv.visitInsn(Opcodes.POP);
        });

        byte[] result = RenderThreadRedirector.redirect("com.example.ClInterop", source);
        List<String> calls = collectMethodCalls(result);
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("org/lwjgl/opencl/CLContext.create")
                        && c.contains("Lorg/lwjgl/opengl/Drawable;")),
                "System 域调用点描述符必须保持 lwjgl 原样: " + calls);
    }

    @Test
    void frameIdentityTypeEntriesAreRemapped() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/SyncFrames", null,
                "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL32",
                "glFenceSync", "(II)Lorg/lwjgl/opengl/GLSync;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 0);
        org.objectweb.asm.Label label = new org.objectweb.asm.Label();
        mv.visitLabel(label);
        mv.visitFrame(Opcodes.F_FULL, 1, new Object[]{"org/lwjgl/opengl/GLSync"}, 0, new Object[0]);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        writer.visitEnd();

        byte[] result = RenderThreadRedirector.redirect("com.example.SyncFrames", writer.toByteArray());
        List<String> frameTypes = new ArrayList<>();
        new ClassReader(result).accept(new ClassVisitor(Opcodes.ASM9, null) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, null) {
                    @Override
                    public void visitFrame(int type, int numLocal, Object[] local,
                                           int numStack, Object[] stack) {
                        if (local != null) {
                            for (Object entry : local) {
                                frameTypes.add(String.valueOf(entry));
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        assertTrue(frameTypes.contains("github/kasuminova/ssoptimizer/bridge/opengl/GLSync"),
                "帧条目中的身份类型必须改写为 bridge: " + frameTypes);
        assertFalse(frameTypes.contains("org/lwjgl/opengl/GLSync"),
                "帧条目不得残留 lwjgl GLSync: " + frameTypes);
    }
}
