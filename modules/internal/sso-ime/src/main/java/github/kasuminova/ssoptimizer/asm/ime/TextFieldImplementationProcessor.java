package github.kasuminova.ssoptimizer.asm.ime;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.objectweb.asm.*;

/**
 * 混淆文本框的构造与焦点回调需要按映射签名在精确返回点注入，无法使用 Mixin 稳定匹配。
 */
public final class TextFieldImplementationProcessor implements AsmClassProcessor {
    public static final String DEFAULT_TARGET_CLASS = GameClassNames.TEXT_FIELD_IMPL;

    private static final String TEXT_FIELD_DESC = GameMixinSignatures.TextFieldIme.TEXT_FIELD_API_DESC;
    private static final String FOCUS_LOST_DESC = GameMixinSignatures.TextFieldIme.TEXT_FIELD_FOCUS_HOOK_DESC;

    private final String targetClass;

    public TextFieldImplementationProcessor() {
        this(DEFAULT_TARGET_CLASS);
    }

    public TextFieldImplementationProcessor(final String targetClass) {
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
                if (!"<init>".equals(name)
                        && !(GameMixinSignatures.TextFieldIme.GRAB_FOCUS.equals(name)
                        && GameMixinSignatures.TextFieldIme.GRAB_FOCUS_DESC.equals(desc))
                        && !(GameMixinSignatures.TextFieldIme.RELEASE_FOCUS.equals(name)
                        && GameMixinSignatures.TextFieldIme.RELEASE_FOCUS_DESC.equals(desc))) {
                    return delegate;
                }

                modified[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            if ("<init>".equals(name)) {
                                visitVarInsn(Opcodes.ALOAD, 0);
                                visitMethodInsn(Opcodes.INVOKESTATIC,
                                        TooltipTextFieldFactoryProcessor.HOOK_OWNER,
                                        "registerCreatedTextField",
                                        "(" + TEXT_FIELD_DESC + ")V",
                                        false);
                            } else if (GameMixinSignatures.TextFieldIme.GRAB_FOCUS.equals(name)) {
                                visitVarInsn(Opcodes.ALOAD, 0);
                                visitMethodInsn(Opcodes.INVOKESTATIC,
                                        TooltipTextFieldFactoryProcessor.HOOK_OWNER,
                                        "onTextFieldFocusGained",
                                        FOCUS_LOST_DESC,
                                        false);
                            } else if (GameMixinSignatures.TextFieldIme.RELEASE_FOCUS.equals(name)) {
                                visitVarInsn(Opcodes.ALOAD, 0);
                                visitMethodInsn(Opcodes.INVOKESTATIC,
                                        TooltipTextFieldFactoryProcessor.HOOK_OWNER,
                                        "onTextFieldFocusLost",
                                        FOCUS_LOST_DESC,
                                        false);
                            }
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}