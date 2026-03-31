package github.kasuminova.ssoptimizer.font;

import github.kasuminova.ssoptimizer.agent.AsmClassProcessor;
import github.kasuminova.ssoptimizer.agent.AsmCommonSuperClassResolver;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * Injects a fast path into the game's resource loader so managed original-font
 * resources can be served from generated in-memory BMFont artifacts.
 */
public final class OriginalFontResourceStreamProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS =
            "com/fs/util/ooOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO";
    public static final String TARGET_METHOD = "String";
    public static final String TARGET_DESC   = "(Ljava/lang/String;)Ljava/io/InputStream;";
    public static final String HELPER_OWNER  = "github/kasuminova/ssoptimizer/font/OriginalGameFontOverrides";
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