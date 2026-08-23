package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 目标类：{@code org/dark/shaders/light/LightShader}（GraphicsLib 模组）。<br>
 * 注入位置：
 * <ul>
 * <li>无参构造器 {@code <init>()V} 全部 RETURN 前：ALOAD 0 + GETFIELD
 * lightTex/normalTex/hdrTex/hdrTex2/hdrTex3/bloomMips 调
 * {@code GlLedgerHooks.noteLightShaderCreated}；</li>
 * <li>{@code destroy()V} HEAD：ALOAD 0 调 {@code noteLightShaderDestroyed}（对称移除）。</li>
 * </ul>
 * 注入动机：GL 显存分类账本计量 LightShader 构造器直接分配的五张 RT 纹理
 * （R32F 1D 查找表、RGB8/RGB16 RTT 纹理、bloom 半尺寸纹理，均绕过 LazyTextureManager）。<br>
 * <p>
 * 为什么不用 Mixin：Mixin 0.8.7 在 EnvironmentStateTweaker 初始化时（模组 jar 挂载到
 * LaunchClassLoader 之前）一次性 prepare 全部 config，模组类字节不可得导致 MixinInfo
 * 永久失效（运行时实测：{@code @Mixin target org.dark.shaders.light.LightShader
 * was not found}）。ASM 处理器在类实际加载时介入，不受解析时序影响。<br>
 * <p>
 * 处理器落在 sso-loading 的原因见 {@link ShaderLibLedgerProcessor}。
 */
public final class LightShaderLedgerProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = "org/dark/shaders/light/LightShader";
    public static final String HOOK_OWNER = ShaderLibLedgerProcessor.HOOK_OWNER;

    public static final String CTOR_DESC = "()V";
    public static final String DESTROY_METHOD = "destroy";
    public static final String DESTROY_DESC = "()V";

    public static final String NOTE_CREATED = "noteLightShaderCreated";
    public static final String NOTE_CREATED_DESC = "(Ljava/lang/Object;IIIIII)V";
    public static final String NOTE_DESTROYED = "noteLightShaderDestroyed";
    public static final String NOTE_DESTROYED_DESC = "(Ljava/lang/Object;)V";

    /** 构造器 RETURN 处读取的实例字段（顺序即钩子参数顺序）。 */
    private static final String[] CTOR_FIELDS = {
            "lightTex", "normalTex", "hdrTex", "hdrTex2", "hdrTex3", "bloomMips"
    };

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
                if ("<init>".equals(name) && CTOR_DESC.equals(descriptor)) {
                    modified[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitInsn(final int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                // 栈布局对齐 (Object, I x6)：首参 this，随后每字段自带接收者
                                mv.visitVarInsn(Opcodes.ALOAD, 0);
                                for (final String field : CTOR_FIELDS) {
                                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                                    mv.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, field, "I");
                                }
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER,
                                        NOTE_CREATED, NOTE_CREATED_DESC, false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                if (DESTROY_METHOD.equals(name) && DESTROY_DESC.equals(descriptor)) {
                    modified[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER,
                                    NOTE_DESTROYED, NOTE_DESTROYED_DESC, false);
                        }
                    };
                }
                return delegate;
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}
