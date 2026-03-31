package github.kasuminova.ssoptimizer.input.ime;

import github.kasuminova.ssoptimizer.agent.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class TextFieldImplementationProcessor implements AsmClassProcessor {
    public static final String DEFAULT_TARGET_CLASS = "com/fs/starfarer/ui/B";

    private static final String TEXT_FIELD_DESC = "Lcom/fs/starfarer/api/ui/TextFieldAPI;";
    private static final String FOCUS_LOST_DESC = "(Lcom/fs/starfarer/api/ui/TextFieldAPI;)V";

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
                        && !("grabFocus".equals(name) && "(Z)V".equals(desc))
                        && !("releaseFocus".equals(name) && "(Lcom/fs/starfarer/util/super/Object;)V".equals(desc))) {
                    return delegate;
                }

                modified[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            if ("<init>".equals(name)) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        TooltipTextFieldFactoryProcessor.HOOK_OWNER,
                                        "registerCreatedTextField",
                                        "(" + TEXT_FIELD_DESC + ")V",
                                        false);
                            } else if ("grabFocus".equals(name)) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        TooltipTextFieldFactoryProcessor.HOOK_OWNER,
                                        "onTextFieldFocusGained",
                                        FOCUS_LOST_DESC,
                                        false);
                            } else if ("releaseFocus".equals(name)) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
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