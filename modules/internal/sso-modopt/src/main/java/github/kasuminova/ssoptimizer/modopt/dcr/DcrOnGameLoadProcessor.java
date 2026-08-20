package github.kasuminova.ssoptimizer.modopt.dcr;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * L1（一）：改写 DCR {@code data/scripts/combatanalytics/DetailedCombatResultsModPlugin} 的
 * {@code onGameLoad(Z)V}，把 trim 重写循环里对 {@code SerializationManager.saveCombatResult} 的调用
 * redirect 为 {@code SerializationManager.ssoptimizer$collect}（仅入缓存、置脏，不序列化），并在每个
 * {@code RETURN} 前 inject 一次 {@code SerializationManager.ssoptimizer$flush}（合并为一次序列化+压缩）。
 * <p>
 * 动机：原循环每次 saveCombatResult 都全量重序列化整份历史 → O(N²)。redirect+inject 把它降为 O(N)。
 * 两处合成方法由 {@link DcrBatchSaveSynthProcessor} 注入 SerializationManager。
 * <p>
 * 帧安全：redirect 描述符不变（栈形不变）；inject 的 {@code flush()V} 无参无返回、在 return 前栈为空——
 * 两者均帧中立。故用 {@code ClassWriter(reader, 0)} 保留原帧，不触发 {@code COMPUTE_FRAMES}。
 */
public final class DcrOnGameLoadProcessor implements AsmClassProcessor {

    /** 目标类 JVM 内部名。 */
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
