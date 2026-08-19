package github.kasuminova.ssoptimizer.asm.automation;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 目标类：{@code cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin}。<br>
 * 注入位置：私有方法 {@code writeTelemetry(CombatEngineAPI, String, ShipAPI, WeaponAPI)} 的方法入口。<br>
 * 注入动机：ASTD 战斗插件由 Janino 脚本类加载器加载，Mixin 桥会跳过 Janino loader；脚本沙盒也会拦截 ASTD 侧文件写入与反射。<br>
 * 注入效果：在方法入口调用 SSOptimizer 独立 helper 写出截图和 telemetry，成功后直接返回，跳过脚本侧写入。
 */
public final class ASTDAutomationCombatPluginProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = "cn/kasuminova/astd/combat/effect/generic/ASTDAutomationCombatPlugin";
    public static final String TARGET_METHOD = "writeTelemetry";
    public static final String TARGET_DESC = "(Lcom/fs/starfarer/api/combat/CombatEngineAPI;Ljava/lang/String;Lcom/fs/starfarer/api/combat/ShipAPI;Lcom/fs/starfarer/api/combat/WeaponAPI;)V";
    public static final String HELPER_OWNER = "github/kasuminova/ssoptimizer/common/automation/AutomationScreenshotHelper";
    public static final String HELPER_METHOD = "writeAutomationEvidence";
    public static final String HELPER_DESC = TARGET_DESC.replace(")V", ")Z");

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
                if (TARGET_METHOD.equals(name) && TARGET_DESC.equals(descriptor)) {
                    modified[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            final Label continueOriginal = new Label();
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitVarInsn(Opcodes.ALOAD, 2);
                            super.visitVarInsn(Opcodes.ALOAD, 3);
                            super.visitVarInsn(Opcodes.ALOAD, 4);
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    HELPER_OWNER,
                                    HELPER_METHOD,
                                    HELPER_DESC,
                                    false
                            );
                            super.visitJumpInsn(Opcodes.IFEQ, continueOriginal);
                            super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(continueOriginal);
                        }
                    };
                }
                return delegate;
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}