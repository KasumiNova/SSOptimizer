package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GL 显存账本五个 ASM 处理器的变换正确性验证。
 * <p>
 * 与 AITweaksCoreLoaderProcessorTest 同模式：用 ASM 合成最小桩类（字段 + 目标方法），
 * 实跑 {@code process()} 后解析产物字节码，断言注入了预期的 GlLedgerHooks INVOKESTATIC
 * 钩子调用（owner/name/desc 精确匹配）；同时验证未匹配类返回 null、目标方法 desc
 * 不匹配时不改写。桩类按真实模组 jar 的 javap 结果逐字段/方法签名搭建。
 */
class GlLedgerProcessorTransformTest {

    private static final String HOOK = ShaderLibLedgerProcessor.HOOK_OWNER;

    // ---------- ShaderLibLedgerProcessor ----------

    @Test
    void shaderLibInjectsInitMakeFramebufferAndSizeHooks() {
        final byte[] stub = stubClass(ShaderLibLedgerProcessor.TARGET_CLASS, w -> {
            // 静态字段（init 钩子读取）
            for (final String f : new String[]{"shadersAllowed", "buffersAllowed",
                    "auxiliaryBuffer64Bit"}) {
                addField(w, f, "Z", true);
            }
            for (final String f : new String[]{"RTTSizeX", "RTTSizeY", "screenTex",
                    "foregroundBufferTex", "auxiliaryBufferTex"}) {
                addField(w, f, "I", true);
            }
            // public static void init() { return; }
            try (MethodCloseable ignored = addMethod(w, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "init", "()V", mv -> mv.visitInsn(Opcodes.RETURN))) {
                // 方法体在 lambda 内完成
            }
            // public static int makeFramebuffer(int,int,int,int,int) { return 1; }
            try (MethodCloseable ignored = addMethod(w, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "makeFramebuffer", "(IIIII)I", mv -> {
                        mv.visitInsn(Opcodes.ICONST_1);
                        mv.visitInsn(Opcodes.IRETURN);
                    })) {
                // 方法体在 lambda 内完成
            }
            // public static int getInternalWidth() { return 0; } / getInternalHeight 同理
            for (final String m : new String[]{"getInternalWidth", "getInternalHeight"}) {
                try (MethodCloseable ignored = addMethod(w, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        m, "()I", mv -> {
                            mv.visitInsn(Opcodes.ICONST_0);
                            mv.visitInsn(Opcodes.IRETURN);
                        })) {
                    // 方法体在 lambda 内完成
                }
            }
        });

        final byte[] rewritten = new ShaderLibLedgerProcessor().process(stub);
        assertNotNull(rewritten, "ShaderLib 桩类应被注入");

        final List<String> initHooks = hookCalls(rewritten, "init", "()V");
        assertEquals(List.of("noteShaderLibInit:(ZZZIIIII)V"), initHooks,
                "init()V RETURN 前应注入 noteShaderLibInit");

        final List<String> makeFbHooks = hookCalls(rewritten, "makeFramebuffer", "(IIIII)I");
        assertEquals(List.of("noteShaderLibRenderbuffer:(III)V"), makeFbHooks,
                "makeFramebuffer IRETURN 前应注入 noteShaderLibRenderbuffer");
        assertTrue(hasJump(rewritten, "makeFramebuffer", "(IIIII)I", Opcodes.IFLE),
                "makeFramebuffer 钩子应有返回值非 0 守卫（IFLE）");

        assertEquals(List.of("noteShaderLibInternalWidth:(I)V"),
                hookCalls(rewritten, "getInternalWidth", "()I"));
        assertEquals(List.of("noteShaderLibInternalHeight:(I)V"),
                hookCalls(rewritten, "getInternalHeight", "()I"));
    }

    // ---------- LightShaderLedgerProcessor ----------

    @Test
    void lightShaderInjectsConstructorAndDestroyHooks() {
        final byte[] stub = stubClass(LightShaderLedgerProcessor.TARGET_CLASS, w -> {
            for (final String f : new String[]{"lightTex", "normalTex", "hdrTex",
                    "hdrTex2", "hdrTex3", "bloomMips"}) {
                addField(w, f, "I", false);
            }
            defaultConstructor(w);
            try (MethodCloseable ignored = addMethod(w, Opcodes.ACC_PUBLIC,
                    "destroy", "()V", mv -> mv.visitInsn(Opcodes.RETURN))) {
                // 方法体在 lambda 内完成
            }
        });

        final byte[] rewritten = new LightShaderLedgerProcessor().process(stub);
        assertNotNull(rewritten, "LightShader 桩类应被注入");

        assertEquals(List.of("noteLightShaderCreated:(Ljava/lang/Object;IIIIII)V"),
                hookCalls(rewritten, "<init>", "()V"),
                "构造器 RETURN 前应注入 noteLightShaderCreated");
        assertEquals(List.of("noteLightShaderDestroyed:(Ljava/lang/Object;)V"),
                hookCalls(rewritten, "destroy", "()V"),
                "destroy()V 应注入 noteLightShaderDestroyed");
        assertTrue(firstRealInsnIsHook(rewritten, "destroy", "()V",
                "noteLightShaderDestroyed"),
                "destroy 钩子必须位于方法 HEAD（首条真实指令）");
        // 构造器钩子参数来源：6 个实例字段的 GETFIELD
        assertEquals(6, countFieldInsn(rewritten, "<init>", "()V", Opcodes.GETFIELD),
                "构造器钩子应读取全部 6 个实例字段");
    }

    // ---------- BoxShaderCoreLedgerProcessor ----------

    @Test
    void boxShaderCoreInjectsScreenScaleHooks() {
        final byte[] stub = stubClass(BoxShaderCoreLedgerProcessor.TARGET_CLASS, w -> {
            defaultConstructor(w);
            for (final String m : new String[]{"getScreenScaleWidth", "getScreenScaleHeight"}) {
                try (MethodCloseable ignored = addMethod(w, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        m, "()I", mv -> {
                            mv.visitInsn(Opcodes.ICONST_0);
                            mv.visitInsn(Opcodes.IRETURN);
                        })) {
                    // 方法体在 lambda 内完成
                }
            }
        });

        final byte[] rewritten = new BoxShaderCoreLedgerProcessor().process(stub);
        assertNotNull(rewritten, "ShaderCore 桩类应被注入");
        assertEquals(List.of("noteBoxScaleWidth:(I)V"),
                hookCalls(rewritten, "getScreenScaleWidth", "()I"));
        assertEquals(List.of("noteBoxScaleHeight:(I)V"),
                hookCalls(rewritten, "getScreenScaleHeight", "()I"));
    }

    // ---------- BoxRenderingBufferLedgerProcessor ----------

    @Test
    void boxRenderingBufferInjectsConstructorHook() {
        final byte[] stub = stubClass(BoxRenderingBufferLedgerProcessor.TARGET_CLASS, w -> {
            addField(w, "_INTERNAL_FORMAT", "[[I", true);
            addField(w, "texID", "[[I", false);
            addField(w, "scaleSize", "[[I", false);
            addField(w, "bloomPingPongTex", "[I", false);
            addField(w, "currLayerCount", "B", false);
            addField(w, "finished", "[Z", false);
            addField(w, "RBO", "I", false);
            defaultConstructor(w);
        });

        final byte[] rewritten = new BoxRenderingBufferLedgerProcessor().process(stub);
        assertNotNull(rewritten, "BUtil_RenderingBuffer 桩类应被注入");
        assertEquals(List.of("noteBoxRenderingBufferCreated:(Ljava/lang/Object;[[I[[I[[I[II[ZI)V"),
                hookCalls(rewritten, "<init>", "()V"),
                "构造器 RETURN 前应注入 noteBoxRenderingBufferCreated");
        assertEquals(6, countFieldInsn(rewritten, "<init>", "()V", Opcodes.GETFIELD),
                "构造器钩子应读取全部 6 个实例字段");
        assertEquals(1, countFieldInsn(rewritten, "<init>", "()V", Opcodes.GETSTATIC),
                "构造器钩子应读取 _INTERNAL_FORMAT 静态字段");
    }

    // ---------- PublicFboLedgerProcessor ----------

    @Test
    void publicFboInjectsConstructorAndDeleteHooks() {
        final byte[] stub = stubClass(PublicFboLedgerProcessor.TARGET_CLASS, w -> {
            addField(w, "FORMAT", "[[I", true);
            addField(w, "texID", "[I", false);
            addField(w, "finished", "Z", false);
            addField(w, "RBO", "I", false);
            defaultConstructor(w);
            try (MethodCloseable ignored = addMethod(w, Opcodes.ACC_PUBLIC,
                    "delete", "()V", mv -> mv.visitInsn(Opcodes.RETURN))) {
                // 方法体在 lambda 内完成
            }
        });

        final byte[] rewritten = new PublicFboLedgerProcessor().process(stub);
        assertNotNull(rewritten, "PublicFBO 桩类应被注入");
        assertEquals(List.of("notePublicFboCreated:(Ljava/lang/Object;[I[[IZI)V"),
                hookCalls(rewritten, "<init>", "()V"),
                "构造器 RETURN 前应注入 notePublicFboCreated");
        assertEquals(List.of("notePublicFboDeleted:(Ljava/lang/Object;)V"),
                hookCalls(rewritten, "delete", "()V"),
                "delete()V 应注入 notePublicFboDeleted");
        assertTrue(firstRealInsnIsHook(rewritten, "delete", "()V", "notePublicFboDeleted"),
                "delete 钩子必须位于方法 HEAD（首条真实指令）");
    }

    // ---------- 阴性用例 ----------

    @Test
    void unmatchedClassReturnsNull() {
        final byte[] other = stubClass("org/example/NotTheTarget",
                GlLedgerProcessorTransformTest::defaultConstructor);
        assertNull(new ShaderLibLedgerProcessor().process(other));
        assertNull(new LightShaderLedgerProcessor().process(other));
        assertNull(new BoxShaderCoreLedgerProcessor().process(other));
        assertNull(new BoxRenderingBufferLedgerProcessor().process(other));
        assertNull(new PublicFboLedgerProcessor().process(other));
    }

    @Test
    void mismatchedMethodDescLeavesClassUntouched() {
        // 类名匹配但目标方法 desc 漂移（如 init(String)V 而非 init()V）：不得改写
        final AsmClassProcessor[] processors = {
                new ShaderLibLedgerProcessor(),
                new LightShaderLedgerProcessor(),
                new BoxShaderCoreLedgerProcessor(),
                new BoxRenderingBufferLedgerProcessor(),
                new PublicFboLedgerProcessor(),
        };
        final String[][] wrongSigs = {
                {"init", "(Ljava/lang/String;)V"},
                {"destroy", "(I)V"},
                {"getScreenScaleWidth", "(I)I"},
                {"<init>", "(I)V"},
                {"delete", "(I)V"},
        };
        for (int i = 0; i < processors.length; i++) {
            final String target = switch (i) {
                case 0 -> ShaderLibLedgerProcessor.TARGET_CLASS;
                case 1 -> LightShaderLedgerProcessor.TARGET_CLASS;
                case 2 -> BoxShaderCoreLedgerProcessor.TARGET_CLASS;
                case 3 -> BoxRenderingBufferLedgerProcessor.TARGET_CLASS;
                default -> PublicFboLedgerProcessor.TARGET_CLASS;
            };
            final String[] wrong = wrongSigs[i];
            final byte[] stub = stubClass(target, w -> {
                // 不带任何其他目标方法：构造器锚点的处理器只给 <init>(I)V 漂移签名，
                // 非构造器锚点的处理器不给构造器（纯 ASM 处理无需合法构造器）
                try (MethodCloseable ignored = addMethod(w,
                        Opcodes.ACC_PUBLIC | ("<init>".equals(wrong[0]) ? 0 : Opcodes.ACC_STATIC),
                        wrong[0], wrong[1], mv -> {
                            if ("<init>".equals(wrong[0])) {
                                mv.visitVarInsn(Opcodes.ALOAD, 0);
                                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object",
                                        "<init>", "()V", false);
                                mv.visitInsn(Opcodes.RETURN);
                            } else if (wrong[1].endsWith(")I")) {
                                mv.visitInsn(Opcodes.ICONST_0);
                                mv.visitInsn(Opcodes.IRETURN);
                            } else {
                                mv.visitInsn(Opcodes.RETURN);
                            }
                        })) {
                    // 方法体在 lambda 内完成
                }
            });
            assertNull(processors[i].process(stub),
                    target + " 目标方法 desc 漂移时不应改写（" + wrong[0] + wrong[1] + "）");
        }
    }

    // ---------- 桩类合成与产物解析工具 ----------

    /** 合成最小桩类（public，super=Object，无构造器除非 body 自行添加）。 */
    private static byte[] stubClass(final String internalName, final Consumer<ClassWriter> body) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        body.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addField(final ClassWriter w, final String name, final String desc,
                                 final boolean isStatic) {
        final FieldVisitor fv = w.visitField(
                (isStatic ? Opcodes.ACC_STATIC : 0), name, desc, null, null);
        fv.visitEnd();
    }

    private static void defaultConstructor(final ClassWriter w) {
        try (MethodCloseable ignored = addMethod(w, Opcodes.ACC_PUBLIC, "<init>", "()V", mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
        })) {
            // 方法体在 lambda 内完成
        }
    }

    /** 以 try-with-resources 形态写方法：lambda 填方法体，close 时 visitMaxs/visitEnd。 */
    private static MethodCloseable addMethod(final ClassWriter w, final int access,
                                             final String name, final String desc,
                                             final Consumer<MethodVisitor> body) {
        final MethodVisitor mv = w.visitMethod(access, name, desc, null, null);
        mv.visitCode();
        body.accept(mv);
        return new MethodCloseable(mv);
    }

    private record MethodCloseable(MethodVisitor mv) implements AutoCloseable {
        @Override
        public void close() {
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }

    /** 收集指定方法内全部指向 GlLedgerHooks 的 INVOKESTATIC 调用（"name:desc" 形态）。 */
    private static List<String> hookCalls(final byte[] classBytes, final String methodName,
                                          final String methodDesc) {
        final List<String> calls = new ArrayList<>();
        visitMethod(classBytes, methodName, methodDesc, new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitMethodInsn(final int opcode, final String owner, final String name,
                                        final String descriptor, final boolean isInterface) {
                if (opcode == Opcodes.INVOKESTATIC && HOOK.equals(owner)) {
                    calls.add(name + ':' + descriptor);
                }
            }
        });
        return calls;
    }

    private static boolean hasJump(final byte[] classBytes, final String methodName,
                                   final String methodDesc, final int jumpOpcode) {
        final boolean[] found = {false};
        visitMethod(classBytes, methodName, methodDesc, new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitJumpInsn(final int opcode, final org.objectweb.asm.Label label) {
                if (opcode == jumpOpcode) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    private static int countFieldInsn(final byte[] classBytes, final String methodName,
                                      final String methodDesc, final int fieldOpcode) {
        final int[] count = {0};
        visitMethod(classBytes, methodName, methodDesc, new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitFieldInsn(final int opcode, final String owner, final String name,
                                       final String descriptor) {
                if (opcode == fieldOpcode) {
                    count[0]++;
                }
            }
        });
        return count[0];
    }

    /** 断言方法 HEAD 的前两条真实指令（跳过 label/line/frame）即 ALOAD 0 + 钩子 INVOKESTATIC。 */
    private static boolean firstRealInsnIsHook(final byte[] classBytes, final String methodName,
                                               final String methodDesc, final String hookName) {
        final List<String> firstTwo = new ArrayList<>(2);
        visitMethod(classBytes, methodName, methodDesc, new MethodVisitor(Opcodes.ASM9) {
            @Override
            public void visitVarInsn(final int opcode, final int varIndex) {
                record("VAR" + opcode + ':' + varIndex);
            }

            @Override
            public void visitMethodInsn(final int opcode, final String owner, final String name,
                                        final String descriptor, final boolean isInterface) {
                record("CALL" + opcode + ':' + owner + '.' + name + descriptor);
            }

            @Override
            public void visitInsn(final int opcode) {
                record("INSN" + opcode);
            }

            @Override
            public void visitFieldInsn(final int opcode, final String owner, final String name,
                                       final String descriptor) {
                record("FIELD" + opcode);
            }

            @Override
            public void visitJumpInsn(final int opcode, final org.objectweb.asm.Label label) {
                record("JUMP" + opcode);
            }

            private void record(final String encoded) {
                if (firstTwo.size() < 2) {
                    firstTwo.add(encoded);
                }
            }
        });
        return firstTwo.size() == 2
                && firstTwo.get(0).equals("VAR" + Opcodes.ALOAD + ":0")
                && firstTwo.get(1).equals("CALL" + Opcodes.INVOKESTATIC + ':'
                        + HOOK + '.' + hookName + "(Ljava/lang/Object;)V");
    }

    private static void visitMethod(final byte[] classBytes, final String methodName,
                                    final String methodDesc, final MethodVisitor visitor) {
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                                             final String descriptor, final String signature,
                                             final String[] exceptions) {
                if (methodName.equals(name) && methodDesc.equals(descriptor)) {
                    return visitor;
                }
                return null;
            }
        }, 0);
    }
}
