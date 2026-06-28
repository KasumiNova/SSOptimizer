package github.kasuminova.ssoptimizer.modopt.dcr;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * L2 压缩处理器：将 DCR {@code data/scripts/combatanalytics/util/CompressionUtil} 的
 * {@code compress(String):byte[]} 与 {@code decompress(byte[]):String} 方法体改写为委派至
 * {@link DcrCompressionHelper}（Zstd 写 + Deflate 读回退）。
 * <p>
 * 动机：DCR 读档/存档热点的压缩段为 {@code Deflater} 级别 9（最慢档）。在 {@code byte[]} 边界替换内核，
 * 使 DCR 自身（非标准）的 Base64 层原封不动地包在外侧（对本替换透明），避免复刻其 Base64。
 * <p>
 * 实现：用 asm-tree 将两个目标方法体替换为线性的「{@code ALOAD 0; INVOKESTATIC helper; (A)RETURN}」，
 * 清除其原 try/catch；其余方法（如 assemble/checksum）连同其栈帧原样保留。新体无分支故无需栈帧，
 * 用 {@link ClassWriter#COMPUTE_MAXS} 重算 max（不重算帧），规避对既有方法跑 {@code COMPUTE_FRAMES} 的风险。
 */
public final class DcrCompressionProcessor implements AsmClassProcessor {

    /** 目标类 JVM 内部名。 */
    public static final String TARGET_CLASS = "data/scripts/combatanalytics/util/CompressionUtil";

    private static final String HELPER = "github/kasuminova/ssoptimizer/modopt/dcr/DcrCompressionHelper";

    private static final String COMPRESS_NAME = "compress";
    private static final String COMPRESS_DESC = "(Ljava/lang/String;)[B";
    private static final String DECOMPRESS_NAME = "decompress";
    private static final String DECOMPRESS_DESC = "([B)Ljava/lang/String;";

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final ClassNode node = new ClassNode();
        reader.accept(node, 0);

        boolean modified = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals(COMPRESS_NAME) && method.desc.equals(COMPRESS_DESC)) {
                replaceWithHelperDelegation(method, COMPRESS_NAME, COMPRESS_DESC, Opcodes.ARETURN);
                modified = true;
            } else if (method.name.equals(DECOMPRESS_NAME) && method.desc.equals(DECOMPRESS_DESC)) {
                replaceWithHelperDelegation(method, DECOMPRESS_NAME, DECOMPRESS_DESC, Opcodes.ARETURN);
                modified = true;
            }
        }

        if (!modified) {
            return null;
        }

        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * 将方法体替换为「加载首个参数 → 调用 helper 同名方法 → 返回其结果」。
     *
     * @param method   目标方法节点
     * @param name     helper 上的同名方法（与 DCR 同名同签名，故 redirect 后描述符不变）
     * @param desc     方法描述符
     * @param returnOp 返回指令（引用类型用 {@link Opcodes#ARETURN}）
     */
    private static void replaceWithHelperDelegation(final MethodNode method,
                                                    final String name,
                                                    final String desc,
                                                    final int returnOp) {
        method.instructions.clear();
        if (method.tryCatchBlocks != null) {
            method.tryCatchBlocks.clear();
        }
        method.localVariables = null;

        final InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, name, desc, false));
        body.add(new InsnNode(returnOp));
        method.instructions.add(body);
    }
}
