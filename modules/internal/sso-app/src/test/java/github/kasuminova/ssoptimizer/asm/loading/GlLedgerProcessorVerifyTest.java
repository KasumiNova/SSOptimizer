package github.kasuminova.ssoptimizer.asm.loading;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * GL 显存账本五个 ASM 处理器产物的字节码结构校验回归测试。
 * <p>
 * 与 {@link GlLedgerProcessorTransformTest}（断言钩子注入位置/参数）互补：
 * 本测试用 {@link CheckClassAdapter#verify} 对产物做全量数据流分析
 * （analyzer 级别，含栈形状），确保注入指令栈布局合法——
 * 回归 {@code LightShaderLedgerProcessor} 曾在构造器 RETURN 前漏发
 * 接收者 ALOAD 0、导致 GETFIELD 把 int 当 objectref 弹栈
 * （verify:none 下解释器 SIGSEGV）的事故。
 * <p>
 * 桩类全部用 ASM 合成（禁止真实模组字节）；校验类加载器用测试 classpath，
 * 可解析产物引用的 GlLedgerHooks。
 */
class GlLedgerProcessorVerifyTest {

    @Test
    void shaderLibOutputPassesDataFlowVerification() {
        final byte[] stub = stubClass(ShaderLibLedgerProcessor.TARGET_CLASS, w -> {
            for (final String f : new String[]{"shadersAllowed", "buffersAllowed",
                    "auxiliaryBuffer64Bit"}) {
                addField(w, f, "Z", true);
            }
            for (final String f : new String[]{"RTTSizeX", "RTTSizeY", "screenTex",
                    "foregroundBufferTex", "auxiliaryBufferTex"}) {
                addField(w, f, "I", true);
            }
            addMethod(w, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "init", "()V",
                    mv -> mv.visitInsn(Opcodes.RETURN));
            addMethod(w, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "makeFramebuffer", "(IIIII)I",
                    mv -> {
                        mv.visitInsn(Opcodes.ICONST_1);
                        mv.visitInsn(Opcodes.IRETURN);
                    });
            for (final String m : new String[]{"getInternalWidth", "getInternalHeight"}) {
                addMethod(w, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, m, "()I", mv -> {
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.IRETURN);
                });
            }
        });
        assertVerifies("ShaderLib", new ShaderLibLedgerProcessor().process(stub));
    }

    @Test
    void lightShaderOutputPassesDataFlowVerification() {
        // 结构贴近真实 LightShader：6 个 int 实例字段 + 构造器内 try/catch
        // 且 catch 块含早退 RETURN（真实类有 6 处 RETURN，含异常路径早退）
        final byte[] stub = stubClass(LightShaderLedgerProcessor.TARGET_CLASS, w -> {
            for (final String f : new String[]{"lightTex", "normalTex", "hdrTex",
                    "hdrTex2", "hdrTex3", "bloomMips"}) {
                addField(w, f, "I", false);
            }
            addMethod(w, Opcodes.ACC_PUBLIC, "<init>", "()V", mv -> {
                final Label tryStart = new Label();
                final Label tryEnd = new Label();
                final Label handler = new Label();
                final Label normalReturn = new Label();
                mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Exception");
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object",
                        "<init>", "()V", false);
                mv.visitLabel(tryStart);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitFieldInsn(Opcodes.PUTFIELD,
                        LightShaderLedgerProcessor.TARGET_CLASS, "lightTex", "I");
                mv.visitLabel(tryEnd);
                mv.visitLabel(normalReturn);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitInsn(Opcodes.ICONST_2);
                mv.visitFieldInsn(Opcodes.PUTFIELD,
                        LightShaderLedgerProcessor.TARGET_CLASS, "normalTex", "I");
                mv.visitInsn(Opcodes.RETURN);
                mv.visitLabel(handler);
                // catch 块早退 RETURN：帧内栈顶为异常引用
                mv.visitInsn(Opcodes.POP);
                mv.visitInsn(Opcodes.RETURN);
            });
            addMethod(w, Opcodes.ACC_PUBLIC, "destroy", "()V",
                    mv -> mv.visitInsn(Opcodes.RETURN));
        });
        assertVerifies("LightShader", new LightShaderLedgerProcessor().process(stub));
    }

    @Test
    void boxShaderCoreOutputPassesDataFlowVerification() {
        final byte[] stub = stubClass(BoxShaderCoreLedgerProcessor.TARGET_CLASS, w -> {
            defaultConstructor(w);
            for (final String m : new String[]{"getScreenScaleWidth", "getScreenScaleHeight"}) {
                addMethod(w, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, m, "()I", mv -> {
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.IRETURN);
                });
            }
        });
        assertVerifies("ShaderCore", new BoxShaderCoreLedgerProcessor().process(stub));
    }

    @Test
    void boxRenderingBufferOutputPassesDataFlowVerification() {
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
        assertVerifies("BUtil_RenderingBuffer",
                new BoxRenderingBufferLedgerProcessor().process(stub));
    }

    @Test
    void publicFboOutputPassesDataFlowVerification() {
        final byte[] stub = stubClass(PublicFboLedgerProcessor.TARGET_CLASS, w -> {
            addField(w, "FORMAT", "[[I", true);
            addField(w, "texID", "[I", false);
            addField(w, "finished", "Z", false);
            addField(w, "RBO", "I", false);
            defaultConstructor(w);
            addMethod(w, Opcodes.ACC_PUBLIC, "delete", "()V",
                    mv -> mv.visitInsn(Opcodes.RETURN));
        });
        assertVerifies("PublicFBO", new PublicFboLedgerProcessor().process(stub));
    }

    // ---------- 校验与桩类合成工具 ----------

    /** 对产物做 CheckClassAdapter 全量校验（含数据流），断言无 AnalyzerException。 */
    private static void assertVerifies(final String tag, final byte[] rewritten) {
        assertNotNull(rewritten, tag + " 桩类应被注入");
        final StringWriter dump = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(rewritten),
                GlLedgerProcessorVerifyTest.class.getClassLoader(), false, new PrintWriter(dump));
        final String out = dump.toString();
        assertFalse(out.contains("AnalyzerException") || out.contains("Error at instruction"),
                tag + " 产物数据流校验失败：\n" + out);
    }

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
        addMethod(w, Opcodes.ACC_PUBLIC, "<init>", "()V", mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
        });
    }

    private static void addMethod(final ClassWriter w, final int access,
                                  final String name, final String desc,
                                  final Consumer<MethodVisitor> body) {
        final MethodVisitor mv = w.visitMethod(access, name, desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
