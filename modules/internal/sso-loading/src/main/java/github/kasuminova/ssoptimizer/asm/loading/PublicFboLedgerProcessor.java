package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 目标类：{@code org/boxutil/units/standard/misc/PublicFBO}（BoxUtil 模组
 * "public framebuffer"）。<br>
 * 注入位置：
 * <ul>
 * <li>无参构造器 {@code <init>()V} 全部 RETURN 前：ALOAD 0 + GETFIELD
 * texID/finished/RBO + GETSTATIC FORMAT，调
 * {@code GlLedgerHooks.notePublicFboCreated}（finished=false 即构造失败、构造器
 * 内部已 delete()，钩子侧不入账）；</li>
 * <li>{@code delete()V} HEAD：ALOAD 0 调 {@code notePublicFboDeleted}（对称移除）。</li>
 * </ul>
 * 注入动机：GL 显存分类账本计量 PublicFBO 的附件纹理（屏幕缩放分辨率
 * glTexImage2D，内部格式取 FORMAT[i][0]）与 DEPTH24_STENCIL8 renderbuffer。
 * 尺寸取自 {@link BoxShaderCoreLedgerProcessor} 缓存的屏幕缩放分辨率（与构造器内
 * 实际调用 {@code ShaderCore.getScreenScaleWidth/Height} 同源）。<br>
 * <p>
 * 为什么不用 Mixin：Mixin 0.8.7 在 EnvironmentStateTweaker 初始化时（模组 jar 挂载到
 * LaunchClassLoader 之前）一次性 prepare 全部 config，模组类字节不可得导致 MixinInfo
 * 永久失效（运行时实测：{@code @Mixin target org.boxutil.units.standard.misc.PublicFBO
 * was not found}）。ASM 处理器在类实际加载时介入，不受解析时序影响。<br>
 * <p>
 * 处理器落在 sso-loading 的原因见 {@link ShaderLibLedgerProcessor}。
 */
public final class PublicFboLedgerProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = "org/boxutil/units/standard/misc/PublicFBO";
    public static final String HOOK_OWNER = ShaderLibLedgerProcessor.HOOK_OWNER;

    public static final String CTOR_DESC = "()V";
    public static final String DELETE_METHOD = "delete";
    public static final String DELETE_DESC = "()V";

    public static final String NOTE_CREATED = "notePublicFboCreated";
    public static final String NOTE_CREATED_DESC = "(Ljava/lang/Object;[I[[IZI)V";
    public static final String NOTE_DELETED = "notePublicFboDeleted";
    public static final String NOTE_DELETED_DESC = "(Ljava/lang/Object;)V";

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
                                mv.visitVarInsn(Opcodes.ALOAD, 0);                      // self
                                getField(mv, "texID", "[I");
                                mv.visitFieldInsn(Opcodes.GETSTATIC, TARGET_CLASS, "FORMAT", "[[I");
                                getField(mv, "finished", "Z");
                                getField(mv, "RBO", "I");
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER,
                                        NOTE_CREATED, NOTE_CREATED_DESC, false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                if (DELETE_METHOD.equals(name) && DELETE_DESC.equals(descriptor)) {
                    modified[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER,
                                    NOTE_DELETED, NOTE_DELETED_DESC, false);
                        }
                    };
                }
                return delegate;
            }

            private void getField(final MethodVisitor mv, final String field, final String desc) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, field, desc);
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}
