package github.kasuminova.ssoptimizer.asm.ime;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.objectweb.asm.*;

/**
 * 设置界面文本框工厂的 ASM 处理器，在 {@code createTextField()} 返回后注册 IME 支持。
 *
 * <p>注入目标：{@code com.fs.starfarer.settings.StarfarerSettings$1.createTextField()}<br>
 * 注入动机：设置界面使用匿名内部类创建文本框，Mixin 无法精确匹配匿名类；
 * 需要在文本框创建后调用 {@code TextFieldImeHooks.registerCreatedTextField()} 进行 IME 注册。<br>
 * 注入效果：在方法每个 ARETURN 前插入 DUP + INVOKESTATIC 调用。</p>
 */
public final class SettingsTextFieldFactoryProcessor implements AsmClassProcessor {
    public static final String DEFAULT_TARGET_CLASS = GameClassNames.STARFARER_SETTINGS_TEXT_FIELD_OWNER;

    private static final String TEXT_FIELD_DESC = GameMixinSignatures.TextFieldIme.TEXT_FIELD_API_DESC;

    private final String targetClass;

    public SettingsTextFieldFactoryProcessor() {
        this(DEFAULT_TARGET_CLASS);
    }

    public SettingsTextFieldFactoryProcessor(final String targetClass) {
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
                if (!GameMixinSignatures.TextFieldIme.CREATE_TEXT_FIELD.equals(name) || !desc.endsWith(TEXT_FIELD_DESC)) {
                    return delegate;
                }

                modified[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.ARETURN) {
                            super.visitInsn(Opcodes.DUP);
                            visitMethodInsn(Opcodes.INVOKESTATIC, TooltipTextFieldFactoryProcessor.HOOK_OWNER,
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