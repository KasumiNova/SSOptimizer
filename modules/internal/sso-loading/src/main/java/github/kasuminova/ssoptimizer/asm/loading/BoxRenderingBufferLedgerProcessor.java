package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 目标类：{@code org/boxutil/backends/buffer/BUtil_RenderingBuffer}（BoxUtil 模组
 * {@code jars/backends/BoxUtilImpl.jar}，ShaderCore 自建 "rendering framebuffer-0/1"
 * 的实际载体）。<br>
 * 注入位置：无参构造器 {@code <init>()V} 全部 RETURN 前：ALOAD 0 + GETFIELD
 * texID/scaleSize/bloomPingPongTex/currLayerCount/finished/RBO + GETSTATIC
 * _INTERNAL_FORMAT，调 {@code GlLedgerHooks.noteBoxRenderingBufferCreated}。<br>
 * 注入动机：GL 显存分类账本计量 BoxUtil 渲染双层缓冲的附件纹理（全分辨率
 * glTexStorage2D）、bloom ping-pong 链与 DEPTH_COMPONENT16 renderbuffer
 * （均绕过 LazyTextureManager）。<b>只计分配峰值</b>：{@code delete(int)} 为逐层
 * 部分删除且 bloomPingPongTex[0] 与 texID[1][0] 别名共享，逐层对称必然双减，
 * 故不提供移除钩子（该对象正常路径全局长存，峰值即实况）。<br>
 * <p>
 * 为什么不用 Mixin：Mixin 0.8.7 在 EnvironmentStateTweaker 初始化时（模组 jar 挂载到
 * LaunchClassLoader 之前）一次性 prepare 全部 config，模组类字节不可得导致 MixinInfo
 * 永久失效（运行时实测：{@code @Mixin target
 * org.boxutil.backends.buffer.BUtil_RenderingBuffer was not found}）。ASM 处理器在
 * 类实际加载时介入，不受解析时序影响。<br>
 * <p>
 * 处理器落在 sso-loading 的原因见 {@link ShaderLibLedgerProcessor}。
 */
public final class BoxRenderingBufferLedgerProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = "org/boxutil/backends/buffer/BUtil_RenderingBuffer";
    public static final String HOOK_OWNER = ShaderLibLedgerProcessor.HOOK_OWNER;

    public static final String CTOR_DESC = "()V";
    public static final String NOTE_CREATED = "noteBoxRenderingBufferCreated";
    public static final String NOTE_CREATED_DESC = "(Ljava/lang/Object;[[I[[I[[I[II[ZI)V";

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                return "java/lang/Object";
            }
        };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access,
                                             final String name,
                                             final String descriptor,
                                             final String signature,
                                             final String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"<init>".equals(name) || !CTOR_DESC.equals(descriptor)) {
                    return delegate;
                }
                modified[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            mv.visitVarInsn(Opcodes.ALOAD, 0);                          // self
                            getField(mv, "texID", "[[I");
                            mv.visitFieldInsn(Opcodes.GETSTATIC, TARGET_CLASS,
                                    "_INTERNAL_FORMAT", "[[I");
                            getField(mv, "scaleSize", "[[I");
                            getField(mv, "bloomPingPongTex", "[I");
                            getField(mv, "currLayerCount", "B");   // 栈上自动按 int 解释
                            getField(mv, "finished", "[Z");
                            getField(mv, "RBO", "I");
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER,
                                    NOTE_CREATED, NOTE_CREATED_DESC, false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    private static void getField(final MethodVisitor mv, final String field, final String desc) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, field, desc);
    }
}
