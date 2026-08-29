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
    void fontAtlasGlIsExcludedFromRedirect() {
        // 字体动态图集的真实 GL 操作体（bridge/opengl/FontAtlasGl）必须调真 GL：
        // 该类在渲染线程执行纹理创建/上传/删除，若被重定向成 bridge 录制调用，
        // 真实上传会被延迟到后续帧且绑定交错污染其他纹理（实机横条/贴图污染症状）。
        // 用真实类名现造字节码，断言其裸 lwjgl 调用点不被改写。
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

    @Test
    void gluSphereDrawIsRedirectedToGluSupport() {
        // ApproLight/LunaLib 等模组的 Sphere.draw 崩溃签名：Sphere 在 System 域，
        // 内部裸调真实 GL11。调用类只引用 Sphere 而无任何 org/lwjgl/opengl 直接引用，
        // 同时验证 GLU 前缀预筛必须放行此类（否则整类跳过改写）。
        byte[] source = buildClass("com/example/UsesGluSphere", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL); // sphere receiver
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
        // setDrawStyle/setNormals/setOrientation/setTextureFlag 是纯 Java 配置，
        // 必须保持同步原样执行（配置完成后 draw 才入队录制，顺序语义依赖这一点）
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

    // ------------------------------------------------------------------
    // invokedynamic 方法句柄（lambda/方法引用）的镜像表白名单判据
    // ------------------------------------------------------------------

    /** 收集全部 invokedynamic bootstrap 参数中引用 lwjgl 的方法句柄（断言用）。 */
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

    /** LambdaMetafactory 形态的 invokedynamic（javac 方法引用产物）：{@code Owner::method}。 */
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
        // BoxUtil 1.0.6 GLWrapper$Drawcall.init 崩溃根因：方法引用（indy Handle）的
        // owner 改写必须与 visitMethodInsn 共用镜像表白名单——桥未镜像的句柄盲改 owner
        // 会在 LambdaMetafactory 链接期解析桥类不存在的方法签名，NoSuchMethodError
        byte[] source = buildClass("com/example/LambdaRefs", mv -> {
            visitLambdaRef(mv, "org/lwjgl/opengl/GL11", "glDrawArrays", "(III)V");
            visitLambdaRef(mv, "org/lwjgl/opengl/GL12", "glDrawRangeElements", "(IIIIIJ)V");
            visitLambdaRef(mv, "org/lwjgl/opengl/GL11", "glGetTexParameterf", "(II)F");
        });

        byte[] result = RenderThreadRedirector.redirect("com.example.LambdaRefs", source);
        List<String> handles = collectIndyHandles(result);
        assertTrue(handles.contains(
                "github/kasuminova/ssoptimizer/bridge/opengl/GL11.glDrawArrays(III)V"),
                "已镜像句柄必须改写到桥: " + handles);
        assertTrue(handles.contains(
                "github/kasuminova/ssoptimizer/bridge/opengl/GL12.glDrawRangeElements(IIIIIJ)V"),
                "补齐覆盖后的崩溃现场 API 必须改写到桥: " + handles);
        assertTrue(handles.contains("org/lwjgl/opengl/GL11.glGetTexParameterf(II)F"),
                "未镜像句柄必须保持原 owner（链接真实 LWJGL 方法）: " + handles);
    }

    @Test
    void indyHandleToUnmirroredMethodAloneIsNoOp() {
        // 唯一引用是未镜像方法句柄时整类无改写点，原样返回
        byte[] source = buildClass("com/example/LambdaUnmirrored", mv ->
                visitLambdaRef(mv, "org/lwjgl/opengl/GL11", "glGetTexParameterf", "(II)F"));

        byte[] result = RenderThreadRedirector.redirect("com.example.LambdaUnmirrored", source);
        assertSame(source, result, "未镜像方法句柄不得改写（无改写点时原样返回）");
    }

    @Test
    void indyInstantiatedMethodTypeWithIdentityTypeIsRemapped() {
        // BoxUtil Operation$Sync.init 崩溃签名回归：泛型 Fun 接口以具体 GLSync 实例化
        // （XIntLongFun<GLSync>），instantiatedMethodType 含 Lorg/lwjgl/opengl/GLSync;。
        // impl Handle 改写到桥后该 MethodType 必须同步改写，否则 LambdaMetafactory
        // 校验报 LambdaConversionException（lwjgl GLSync 不可 convertible 到 bridge GLSync）
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

    /** 收集 indy bootstrap 参数中的全部 Type 描述符与 Handle（断言用）。 */
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

    /** 带身份类型的 LambdaMetafactory 站点：sam 擦除 Object，instantiated 具体 GLSync。 */
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
        // BoxUtil Operation$Sync.init:3308 崩溃签名回归（glGetSync buffer 形态未镜像
        // 时期的半改写站）：impl Handle 未镜像保持 lwjgl owner 时，instantiatedMethodType
        // 必须同步保持 lwjgl——半改写站必在 LambdaMetafactory 链接期类型不匹配。
        // 用一个桥必然未镜像的假想签名固定该判据（真实缺口修复后仍须守住整站一致性）
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
    void indySiteWithModOwnedIdentityHandleKeepsTypeArgs() {
        // 模组自有 lambda 体（owner 不在改写表）签名引用身份类型时同样整站保留：
        // 类声明不改写，模组方法的 GLSync 参数永远是 lwjgl 类型
        byte[] source = buildClass("com/example/LambdaModOwnedSync", mv ->
                visitSyncLambdaRef(mv, "com/example/SyncHelper", "lambda$sync$0"));

        byte[] result = RenderThreadRedirector.redirect("com.example.LambdaModOwnedSync", source);
        List<String> types = new ArrayList<>();
        List<String> handles = new ArrayList<>();
        collectIndyArgs(result, types, handles);
        assertTrue(handles.contains("com/example/SyncHelper.lambda$sync$0(Lorg/lwjgl/opengl/GLSync;)V"),
                "模组自有句柄必须保持原样: " + handles);
        assertTrue(types.contains("(Lorg/lwjgl/opengl/GLSync;)V"),
                "MethodType 参数必须同步保持 lwjgl 原样: " + types);
    }

    @Test
    void arbSyncFamilyHandlesAreMirrored() {
        // Operation$Sync 的 GL_ARB_sync 回退分支：ARBSync 入改写表后方法引用
        // 必须改写到桥（含描述符身份类型替换），与 GL32 主分支同一语义
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
}
