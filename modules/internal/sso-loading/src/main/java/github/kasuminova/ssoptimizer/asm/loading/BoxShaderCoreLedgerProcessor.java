package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 目标类：{@code org/boxutil/manager/ShaderCore}（BoxUtil 模组）。<br>
 * 注入位置：{@code getScreenScaleWidth()I} / {@code getScreenScaleHeight()I} 全部
 * IRETURN 前：DUP 返回值调 {@code GlLedgerHooks.noteBoxScaleWidth/Height}。<br>
 * 注入动机：GL 显存分类账本；{@code PublicFBO} 不持有自身宽高字段，其构造器的纹理
 * 尺寸取自这两个方法——本处理器把返回值缓存进 GlLedgerHooks，供
 * {@link PublicFboLedgerProcessor} 的构造器 RETURN 埋点按同源尺寸计量
 * （PublicFBO 构造器首句即调用这两个方法，时序上必然先于其 RETURN 钩子）。<br>
 * <p>
 * 为什么不用 Mixin：Mixin 0.8.7 在 EnvironmentStateTweaker 初始化时（模组 jar 挂载到
 * LaunchClassLoader 之前）一次性 prepare 全部 config，模组类字节不可得导致 MixinInfo
 * 永久失效（运行时实测：{@code @Mixin target org.boxutil.manager.ShaderCore
 * was not found}）。ASM 处理器在类实际加载时介入，不受解析时序影响。<br>
 * <p>
 * 处理器落在 sso-loading 的原因见 {@link ShaderLibLedgerProcessor}。
 */
public final class BoxShaderCoreLedgerProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = "org/boxutil/manager/ShaderCore";
    public static final String HOOK_OWNER = ShaderLibLedgerProcessor.HOOK_OWNER;

    public static final String WIDTH_METHOD = "getScreenScaleWidth";
    public static final String HEIGHT_METHOD = "getScreenScaleHeight";
    public static final String INT_RETURN_DESC = "()I";

    public static final String NOTE_WIDTH = "noteBoxScaleWidth";
    public static final String NOTE_HEIGHT = "noteBoxScaleHeight";
    public static final String NOTE_INT_DESC = "(I)V";

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
                final String hook;
                if (WIDTH_METHOD.equals(name) && INT_RETURN_DESC.equals(descriptor)) {
                    hook = NOTE_WIDTH;
                } else if (HEIGHT_METHOD.equals(name) && INT_RETURN_DESC.equals(descriptor)) {
                    hook = NOTE_HEIGHT;
                } else {
                    return delegate;
                }
                modified[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.IRETURN) {
                            mv.visitInsn(Opcodes.DUP);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, hook, NOTE_INT_DESC, false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}
