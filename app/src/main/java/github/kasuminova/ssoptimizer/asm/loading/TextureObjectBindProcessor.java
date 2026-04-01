package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import github.kasuminova.ssoptimizer.bootstrap.AsmCommonSuperClassResolver;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.objectweb.asm.*;

/**
 * 纹理对象绑定方法的 ASM 替换处理器，支持延迟纹理加载（Lazy Texture）。
 *
 * <p>注入目标：{@code com.fs.graphics.TextureObject} 的绑定方法 {@code bind()} 和 ID 获取方法 {@code getTextureId()}<br>
 * 注入动机：游戏的纹理绑定逻辑不支持按需加载和贴图合并；
 * 需要在绑定时插入 {@link github.kasuminova.ssoptimizer.common.loading.LazyTextureManager}
 * 的代理调用以实现纹理延迟加载和合并纹理集。<br>
 * 注入效果：替换绑定方法体为 {@code LazyTextureManager.bindTexture()}，
 * 替换 ID 获取方法体为 {@code LazyTextureManager.getTextureId()}。</p>
 */
public final class TextureObjectBindProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS            = GameClassNames.TEXTURE_OBJECT;
    public static final String TARGET_METHOD           = GameMemberNames.TextureObject.BIND;
    public static final String TARGET_DESC             = "()V";
    public static final String TARGET_ID_GETTER_METHOD = GameMemberNames.TextureObject.GET_TEXTURE_ID;
    public static final String TARGET_ID_GETTER_DESC   = "()I";
    public static final String HELPER_OWNER            = "github/kasuminova/ssoptimizer/common/loading/LazyTextureManager";
    public static final String HELPER_METHOD           = "bindTexture";
    public static final String HELPER_DESC             = "(L" + GameClassNames.TEXTURE_OBJECT + ";I)V";
    public static final String HELPER_ID_METHOD        = "getTextureId";
    public static final String HELPER_ID_DESC          = "(L" + GameClassNames.TEXTURE_OBJECT + ";II)I";

    private static final String TARGET_BIND_TARGET_FIELD = GameMemberNames.TextureObject.BIND_TARGET;
    private static final String TARGET_TEXTURE_ID_FIELD  = GameMemberNames.TextureObject.TEXTURE_ID;

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
                return AsmCommonSuperClassResolver.resolve(type1, type2);
            }
        };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String desc,
                                             final String sig, final String[] ex) {
                final MethodVisitor delegate = super.visitMethod(access, name, desc, sig, ex);
                if (TARGET_METHOD.equals(name) && TARGET_DESC.equals(desc)) {
                    modified[0] = true;
                    return new BindMethodReplacer(delegate);
                }
                if (TARGET_ID_GETTER_METHOD.equals(name) && TARGET_ID_GETTER_DESC.equals(desc)) {
                    modified[0] = true;
                    return new TextureIdMethodReplacer(delegate);
                }
                return delegate;
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    static final class BindMethodReplacer extends MethodVisitor {
        private final MethodVisitor target;

        BindMethodReplacer(final MethodVisitor target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public void visitCode() {
            target.visitCode();
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, TARGET_BIND_TARGET_FIELD, "I");
            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER, HELPER_METHOD, HELPER_DESC, false);
            target.visitInsn(Opcodes.RETURN);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            target.visitMaxs(0, 0);
        }

        @Override
        public void visitEnd() {
            target.visitEnd();
        }

        @Override
        public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
            return target.visitAnnotation(descriptor, visible);
        }
    }

    static final class TextureIdMethodReplacer extends MethodVisitor {
        private final MethodVisitor target;

        TextureIdMethodReplacer(final MethodVisitor target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public void visitCode() {
            target.visitCode();
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, TARGET_BIND_TARGET_FIELD, "I");
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, TARGET_TEXTURE_ID_FIELD, "I");
            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER, HELPER_ID_METHOD, HELPER_ID_DESC, false);
            target.visitInsn(Opcodes.IRETURN);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            target.visitMaxs(0, 0);
        }

        @Override
        public void visitEnd() {
            target.visitEnd();
        }

        @Override
        public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
            return target.visitAnnotation(descriptor, visible);
        }
    }
}