package github.kasuminova.ssoptimizer.asm.ime;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.objectweb.asm.*;

/**
 * 在 Tooltip 文本框工厂返回时注册 IME；工厂返回点无法由 Mixin 精确匹配。
 */
public final class TooltipTextFieldFactoryProcessor implements AsmClassProcessor {
    public static final String DEFAULT_TARGET_CLASS = GameClassNames.STANDARD_TOOLTIP_V2_EXPANDABLE;
    public static final String HOOK_OWNER           = "github/kasuminova/ssoptimizer/common/input/ime/TextFieldImeHooks";

    private static final String TEXT_FIELD_DESC = GameMixinSignatures.TextFieldIme.TEXT_FIELD_API_DESC;

    private final String targetClass;

    public TooltipTextFieldFactoryProcessor() {
        this(DEFAULT_TARGET_CLASS);
    }

    public TooltipTextFieldFactoryProcessor(final String targetClass) {
        this.targetClass = targetClass;
    }

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!targetClass.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1,
                                                 final String type2) {
                return "java/lang/Object";
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
                if (!GameMixinSignatures.TextFieldIme.ADD_TEXT_FIELD.equals(name) || !desc.endsWith(TEXT_FIELD_DESC)) {
                    return delegate;
                }

                modified[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.ARETURN) {
                            super.visitInsn(Opcodes.DUP);
                            visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER,
                                    "registerCreatedTextField", "(" + TEXT_FIELD_DESC + ")V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}