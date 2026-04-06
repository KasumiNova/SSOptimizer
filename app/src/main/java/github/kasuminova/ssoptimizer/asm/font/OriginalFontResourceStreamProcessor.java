package github.kasuminova.ssoptimizer.asm.font;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import github.kasuminova.ssoptimizer.bootstrap.AsmCommonSuperClassResolver;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * 原版字体资源流拦截处理器。
 * <p>
 * 注入目标：{@code com.fs.util.ResourceLoader}<br>
 * 注入动机：需要在不改动上层字体调用逻辑的前提下，把原版字体资源请求转发到
 * 运行时生成的 BMFont 产物；ASM 适合在资源流入口处做统一拦截。<br>
 * 注入效果：方法入口先尝试通过 {@code OriginalGameFontOverrides.openStream()} 返回
 * 自定义字体流，命中时直接返回，未命中则继续走原始资源加载逻辑。
 */
public final class OriginalFontResourceStreamProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS  = GameClassNames.RESOURCE_LOADER;
    public static final String TARGET_METHOD = GameMemberNames.ResourceLoader.OPEN_STREAM;
    public static final String TARGET_DESC   = "(Ljava/lang/String;)Ljava/io/InputStream;";
    public static final String HELPER_OWNER  = "github/kasuminova/ssoptimizer/common/font/OriginalGameFontOverrides";
    public static final String HELPER_METHOD = "openStream";

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1,
                                                 final String type2) {
                return AsmCommonSuperClassResolver.resolve(type1, type2);
            }
        };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access,
                                             final String name,
                                             final String desc,
                                             final String signature,
                                             final String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                if (!TARGET_METHOD.equals(name) || !TARGET_DESC.equals(desc)) {
                    return delegate;
                }

                modified[0] = true;
                final int resourcePathIndex = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
                return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, desc) {
                    @Override
                    protected void onMethodEnter() {
                        visitVarInsn(Opcodes.ALOAD, resourcePathIndex);
                        visitMethodInsn(Opcodes.INVOKESTATIC,
                                HELPER_OWNER,
                                HELPER_METHOD,
                                TARGET_DESC,
                                false);
                        dup();
                        final Label fallThrough = new Label();
                        visitJumpInsn(Opcodes.IFNULL, fallThrough);
                        visitInsn(Opcodes.ARETURN);
                        visitLabel(fallThrough);
                        visitInsn(Opcodes.POP);
                    }
                };
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}