package github.kasuminova.ssoptimizer.asm.render;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.objectweb.asm.*;

/**
 * Mixin 的逐目标注入无法与该处理器所需的全链路 GL owner 重定向共享同一变换上下文，因此使用 ASM。
 */
public final class CombatStateProcessor implements AsmClassProcessor {
    private static final String TARGET_CLASS      = GameClassNames.COMBAT_STATE;
    private static final String GL11_OWNER        = "org/lwjgl/opengl/GL11";
    private static final String FINISH_HOOK_OWNER = "github/kasuminova/ssoptimizer/common/render/CombatStateTraversalHook";

    @Override
    public byte[] process(byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, sig, ex);
                if (!"traverse".equals(name)) {
                    return delegate;
                }

                modified[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (owner.equals(GL11_OWNER) && methodName.equals("glFinish") && methodDesc.equals("()V")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, FINISH_HOOK_OWNER, "callFinishIfEnabled", "()V", false);
                        } else {
                            super.visitMethodInsn(opcode, owner, methodName, methodDesc, itf);
                        }
                    }
                };
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}