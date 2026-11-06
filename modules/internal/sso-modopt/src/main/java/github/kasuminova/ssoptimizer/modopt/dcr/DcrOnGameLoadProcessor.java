package github.kasuminova.ssoptimizer.modopt.dcr;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 将 DCR 读档循环的逐条保存改为批量收集，并在返回前统一刷写。
 * <p>调用替换保持描述符不变，刷写发生在空操作数栈上，因此保留原栈帧。</p>
 */
public final class DcrOnGameLoadProcessor implements AsmClassProcessor {

    public static final String TARGET_CLASS = "data/scripts/combatanalytics/DetailedCombatResultsModPlugin";

    private static final String ON_GAME_LOAD_NAME = "onGameLoad";
    private static final String ON_GAME_LOAD_DESC = "(Z)V";

    private static final String SERIALIZATION_MANAGER = "data/scripts/combatanalytics/SerializationManager";
    private static final String SAVE_NAME = "saveCombatResult";
    private static final String SAVE_DESC = "(Ldata/scripts/combatanalytics/data/CombatResult;)V";
    private static final String COLLECT_NAME = "ssoptimizer$collect";
    private static final String FLUSH_NAME = "ssoptimizer$flush";
    private static final String FLUSH_DESC = "()V";

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        final ClassWriter writer = new ClassWriter(reader, 0);

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!ON_GAME_LOAD_NAME.equals(name) || !ON_GAME_LOAD_DESC.equals(descriptor)) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(final int opcode, final String owner, final String mName,
                                                final String mDesc, final boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && SERIALIZATION_MANAGER.equals(owner)
                                && SAVE_NAME.equals(mName)
                                && SAVE_DESC.equals(mDesc)) {
                            modified[0] = true;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SERIALIZATION_MANAGER,
                                    COLLECT_NAME, SAVE_DESC, false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                    }

                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            modified[0] = true;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SERIALIZATION_MANAGER,
                                    FLUSH_NAME, FLUSH_DESC, false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}
