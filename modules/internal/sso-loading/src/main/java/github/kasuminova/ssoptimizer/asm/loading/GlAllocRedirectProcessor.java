package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Map;
import java.util.Set;

/**
 * GL 分配调用点重定向处理器的共用基座：把目标类中对 {@code org/lwjgl/opengl/*}
 * 的指定 INVOKESTATIC 调用原样改写为 {@code GlLedgerHooks} 同名签名的转发钩子
 * （owner 与 name 替换，desc 不变，栈形状零改动）。
 * <p>
 * 为什么不用 Mixin：Mixin 0.8.7 在 EnvironmentStateTweaker 初始化时（模组 jar 挂载到
 * LaunchClassLoader 之前）一次性 prepare 全部 config，模组类字节不可得导致 MixinInfo
 * 永久失效（运行时实测：{@code @Mixin target ... was not found}）。ASM 处理器在类实际
 * 加载时介入，不受解析时序影响。
 * <p>
 * 与 RenderThreadRedirector 的关系：本处理器在 transformer 链上先于它执行，被改写的
 * 调用点 owner 已是 GlLedgerHooks 而不再匹配 lwjgl 前缀；钩子里的真实 GL 调用
 * （GlLedgerHooks 不在 redirector 排除包内）会被 redirector 照常改写为 bridge 镜像，
 * RT 模式语义与原始调用点一致。
 */
public abstract class GlAllocRedirectProcessor implements AsmClassProcessor {

    /** 钩子类内部名（与既有五个账本处理器一致）。 */
    protected static final String HOOK_OWNER = ShaderLibLedgerProcessor.HOOK_OWNER;

    /** 本处理器命中的目标类集合（内部名）。 */
    protected abstract Set<String> targetClasses();

    /**
     * 重定向表：{@code owner + '.' + name + desc} → GlLedgerHooks 钩子方法名。
     * 钩子的 desc 必须与被替换调用完全一致（转发后记账）。
     */
    protected abstract Map<String, String> redirects();

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!targetClasses().contains(reader.getClassName())) {
            return null;
        }
        final Map<String, String> redirects = redirects();
        final boolean[] modified = {false};
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                return "java/lang/Object";
            }
        };
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                                             final String descriptor, final String signature,
                                             final String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, descriptor,
                        signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(final int opcode, final String owner,
                                                final String insnName, final String insnDesc,
                                                final boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC) {
                            final String hook = redirects.get(owner + '.' + insnName + insnDesc);
                            if (hook != null) {
                                modified[0] = true;
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, hook,
                                        insnDesc, false);
                                return;
                            }
                        }
                        super.visitMethodInsn(opcode, owner, insnName, insnDesc, isInterface);
                    }
                };
            }
        }, 0);
        return modified[0] ? writer.toByteArray() : null;
    }
}
