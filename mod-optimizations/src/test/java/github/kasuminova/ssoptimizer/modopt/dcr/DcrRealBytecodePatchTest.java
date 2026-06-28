package github.kasuminova.ssoptimizer.modopt.dcr;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「patch 成功」证明：对 <b>真实</b> DCR 字节运行三个处理器，断言——
 * (1) 处理器匹配并修改了目标类（patch 应用）；(2) 产物经 {@link CheckClassAdapter} 结构校验合法（可加载）；
 * (3) 关键结构注入到位（合成方法 / redirect / inject / 体替换）。
 * <p>
 * 真实 DCR 类字节随仓库 vendored 于 {@code src/test/resources/dcr/}（MIT 许可，见该目录 README），
 * 故本测试 <b>始终运行</b>（含 CI），不依赖本机游戏安装。栈帧合法性由 {@link DcrBatchSaveIntegrationTest}
 * 在等价转换模式上经 JVM 加载校验证明；此处用无数据流的 CheckClassAdapter 证明真实 DCR 字节的结构良构
 * （不需游戏类型可解析）。
 */
class DcrRealBytecodePatchTest {

    private static final String SM = "data/scripts/combatanalytics/SerializationManager";
    private static final String PLUGIN = "data/scripts/combatanalytics/DetailedCombatResultsModPlugin";
    private static final String COMPRESSION_UTIL = "data/scripts/combatanalytics/util/CompressionUtil";
    private static final String HELPER = "github/kasuminova/ssoptimizer/modopt/dcr/DcrCompressionHelper";

    @Test
    void synthProcessorPatchesRealSerializationManager() throws IOException {
        final ClassNode node = patch(SM, new DcrBatchSaveSynthProcessor());
        assertNotNull(method(node, "ssoptimizer$collect", "(Ldata/scripts/combatanalytics/data/CombatResult;)V"),
                "真实 SerializationManager 应被注入 ssoptimizer$collect");
        assertNotNull(method(node, "ssoptimizer$flush", "()V"),
                "真实 SerializationManager 应被注入 ssoptimizer$flush");
        assertTrue(node.fields.stream().anyMatch(f -> f.name.equals("ssoptimizer$dirty")),
                "真实 SerializationManager 应被注入 ssoptimizer$dirty");
    }

    @Test
    void onGameLoadProcessorPatchesRealPlugin() throws IOException {
        final ClassNode node = patch(PLUGIN, new DcrOnGameLoadProcessor());
        final MethodNode onGameLoad = method(node, "onGameLoad", "(Z)V");
        assertNotNull(onGameLoad, "真实 plugin 应有 onGameLoad(Z)V");
        assertEquals(0, staticCalls(onGameLoad, SM, "saveCombatResult"),
                "真实 onGameLoad 的 saveCombatResult 应全部被 redirect");
        assertEquals(1, staticCalls(onGameLoad, SM, "ssoptimizer$collect"),
                "真实 onGameLoad 应恰有一处 ssoptimizer$collect");
        assertTrue(staticCalls(onGameLoad, SM, "ssoptimizer$flush") >= 1,
                "真实 onGameLoad 应注入 ssoptimizer$flush");
    }

    @Test
    void compressionProcessorPatchesRealCompressionUtil() throws IOException {
        final ClassNode node = patch(COMPRESSION_UTIL, new DcrCompressionProcessor());
        final MethodNode compress = method(node, "compress", "(Ljava/lang/String;)[B");
        final MethodNode decompress = method(node, "decompress", "([B)Ljava/lang/String;");
        assertTrue(delegatesTo(compress, HELPER, "compress"), "真实 compress 应委派 helper");
        assertTrue(delegatesTo(decompress, HELPER, "decompress"), "真实 decompress 应委派 helper");
    }

    /** 读真实类字节 → 运行处理器 → 断言修改 + CheckClassAdapter 结构合法 → 返回解析后的 ClassNode。 */
    private static ClassNode patch(final String internalName, final AsmClassProcessor processor) throws IOException {
        final byte[] original = readVendoredClass(internalName);
        final byte[] transformed = processor.process(original);
        assertNotNull(transformed, "处理器应匹配并修改真实类: " + internalName);
        // 结构良构校验（无数据流，不需游戏类型可解析）：CheckClassAdapter 遍历不抛出即合法。
        new ClassReader(transformed).accept(new CheckClassAdapter(new ClassNode(), false), 0);
        final ClassNode node = new ClassNode();
        new ClassReader(transformed).accept(node, 0);
        return node;
    }

    private static byte[] readVendoredClass(final String internalName) throws IOException {
        final String resource = "/dcr/" + internalName + ".class";
        try (InputStream in = DcrRealBytecodePatchTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "缺少 vendored 的真实 DCR 类字节: " + resource
                    + "（见 src/test/resources/dcr/README.md）");
            return in.readAllBytes();
        }
    }

    private static long staticCalls(final MethodNode m, final String owner, final String name) {
        return Arrays.stream(m.instructions.toArray())
                .filter(i -> i instanceof MethodInsnNode mi
                        && mi.getOpcode() == Opcodes.INVOKESTATIC
                        && mi.owner.equals(owner) && mi.name.equals(name))
                .count();
    }

    private static boolean delegatesTo(final MethodNode m, final String owner, final String name) {
        return m != null && Arrays.stream(m.instructions.toArray())
                .anyMatch(i -> i instanceof MethodInsnNode mi
                        && mi.getOpcode() == Opcodes.INVOKESTATIC
                        && mi.owner.equals(owner) && mi.name.equals(name));
    }

    private static MethodNode method(final ClassNode node, final String name, final String desc) {
        return node.methods.stream()
                .filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst().orElse(null);
    }
}
