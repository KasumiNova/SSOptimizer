package github.kasuminova.ssoptimizer.asm.render;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import org.objectweb.asm.*;

/**
 * 战斗状态遍历方法的 ASM 处理器，替换 {@code glFinish()} 为条件调用。
 *
 * <p>注入目标：{@code com.fs.starfarer.combat.CombatState.traverse()}<br>
 * 注入动机：原始的 {@code traverse()} 方法中调用了 {@code glFinish()}，
 * 这是一个同步阻塞调用，会严重降低 GPU 流水线利用率；
 * Mixin 无法精确替换方法体内的单个方法调用指令。<br>
 * 注入效果：将 {@code GL11.glFinish()} 调用替换为
 * {@link github.kasuminova.ssoptimizer.common.render.CombatStateTraversalHook#callFinishIfEnabled()}
 * 的条件调用，默认跳过 {@code glFinish} 以提升帧率。</p>
 */
public final class CombatStateProcessor implements AsmClassProcessor {
    private static final String TARGET_CLASS      = "com/fs/starfarer/combat/CombatState";
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