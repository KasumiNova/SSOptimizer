package github.kasuminova.ssoptimizer.modopt.dcr;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DcrCompressionProcessor} 的结构验证：对编译好的 {@code CompressionUtil} 夹具运行处理器，
 * 断言 compress/decompress 的方法体已被改写为「{@code ALOAD 0; INVOKESTATIC DcrCompressionHelper; (A)RETURN}」，
 * 且无关方法 checksum 被保留、字节码可重新解析。无反射、无源码 contain。
 */
class DcrCompressionProcessorTest {

    private static final String HELPER = "github/kasuminova/ssoptimizer/modopt/dcr/DcrCompressionHelper";

    @Test
    void replacesCompressBodyWithHelperDelegation() throws IOException {
        final ClassNode node = transformFixture();
        final MethodNode compress = method(node, "compress", "(Ljava/lang/String;)[B");
        assertDelegation(compress, "compress", "(Ljava/lang/String;)[B", Opcodes.ARETURN);
        assertTrue(compress.tryCatchBlocks == null || compress.tryCatchBlocks.isEmpty(),
                "原 try/catch 应在体替换时清除");
    }

    @Test
    void replacesDecompressBodyWithHelperDelegation() throws IOException {
        final ClassNode node = transformFixture();
        final MethodNode decompress = method(node, "decompress", "([B)Ljava/lang/String;");
        assertDelegation(decompress, "decompress", "([B)Ljava/lang/String;", Opcodes.ARETURN);
    }

    @Test
    void preservesUnrelatedMethods() throws IOException {
        final ClassNode node = transformFixture();
        final MethodNode checksum = method(node, "checksum", "([B)I");
        assertNotNull(checksum, "无关方法 checksum 应保留");
        assertTrue(checksum.instructions.size() > 3, "checksum 体应保持不变（含循环）");
    }

    @Test
    void ignoresNonTargetClass() {
        // 任意非目标类字节（用本测试类自身）应返回 null（不修改）。
        final byte[] foreign = readClassBytes(DcrCompressionProcessorTest.class.getName());
        assertNull(new DcrCompressionProcessor().process(foreign));
    }

    private ClassNode transformFixture() throws IOException {
        final byte[] original = readClassBytes("data.scripts.combatanalytics.util.CompressionUtil");
        final byte[] transformed = new DcrCompressionProcessor().process(original);
        assertNotNull(transformed, "目标类应被处理器修改");
        final ClassNode node = new ClassNode();
        new ClassReader(transformed).accept(node, 0);
        return node;
    }

    private static void assertDelegation(final MethodNode m, final String name, final String desc, final int returnOp) {
        final List<AbstractInsnNode> real = realInsns(m);
        assertEquals(3, real.size(), "委派体应为 3 条真实指令");
        assertTrue(real.get(0) instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0,
                "首指令应为 ALOAD 0");
        assertTrue(real.get(1) instanceof MethodInsnNode mi
                        && mi.getOpcode() == Opcodes.INVOKESTATIC
                        && mi.owner.equals(HELPER) && mi.name.equals(name) && mi.desc.equals(desc),
                "第二指令应为 INVOKESTATIC DcrCompressionHelper." + name);
        assertEquals(returnOp, real.get(2).getOpcode(), "末指令应为 (A)RETURN");
    }

    /** 过滤掉 label/line/frame 等伪指令，仅保留真实可执行指令。 */
    private static List<AbstractInsnNode> realInsns(final MethodNode m) {
        final List<AbstractInsnNode> out = new ArrayList<>();
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn.getOpcode() >= 0) {
                out.add(insn);
            }
        }
        return out;
    }

    private static MethodNode method(final ClassNode node, final String name, final String desc) {
        return node.methods.stream()
                .filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst().orElse(null);
    }

    private static byte[] readClassBytes(final String binaryName) {
        final String resource = "/" + binaryName.replace('.', '/') + ".class";
        try (InputStream in = DcrCompressionProcessorTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "找不到类资源: " + resource);
            return in.readAllBytes();
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }
}
