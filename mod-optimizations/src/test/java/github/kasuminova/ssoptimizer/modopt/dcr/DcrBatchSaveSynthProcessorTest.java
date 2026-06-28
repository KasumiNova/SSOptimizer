package github.kasuminova.ssoptimizer.modopt.dcr;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DcrBatchSaveSynthProcessor} 结构验证（基于编译好的 SerializationManager 夹具）：
 * 注入了 {@code ssoptimizer$dirty:Z} 字段与 {@code ssoptimizer$collect/flush} 方法，且幂等。无反射。
 */
class DcrBatchSaveSynthProcessorTest {

    @Test
    void injectsDirtyFieldAndSyntheticMethods() throws IOException {
        final ClassNode node = transformedFixture();
        assertTrue(hasField(node, "ssoptimizer$dirty", "Z"), "应注入 ssoptimizer$dirty:Z 字段");
        assertNotNull(method(node, "ssoptimizer$collect", "(Ldata/scripts/combatanalytics/data/CombatResult;)V"),
                "应注入 ssoptimizer$collect");
        assertNotNull(method(node, "ssoptimizer$flush", "()V"), "应注入 ssoptimizer$flush");
    }

    @Test
    void isIdempotent() throws IOException {
        final byte[] original = readClassBytes("data.scripts.combatanalytics.SerializationManager");
        final byte[] once = new DcrBatchSaveSynthProcessor().process(original);
        assertNotNull(once, "首次应修改");
        final byte[] twice = new DcrBatchSaveSynthProcessor().process(once);
        assertNull(twice, "已注入则二次应返回 null（幂等）");
    }

    @Test
    void ignoresNonTargetClass() {
        final byte[] foreign = readClassBytes(DcrBatchSaveSynthProcessorTest.class.getName());
        assertNull(new DcrBatchSaveSynthProcessor().process(foreign));
    }

    private ClassNode transformedFixture() throws IOException {
        final byte[] original = readClassBytes("data.scripts.combatanalytics.SerializationManager");
        final byte[] transformed = new DcrBatchSaveSynthProcessor().process(original);
        assertNotNull(transformed, "SerializationManager 应被处理器修改");
        final ClassNode node = new ClassNode();
        new ClassReader(transformed).accept(node, 0);
        return node;
    }

    private static boolean hasField(final ClassNode node, final String name, final String desc) {
        return node.fields.stream().anyMatch((FieldNode f) -> f.name.equals(name) && f.desc.equals(desc));
    }

    private static MethodNode method(final ClassNode node, final String name, final String desc) {
        return node.methods.stream()
                .filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst().orElse(null);
    }

    private static byte[] readClassBytes(final String binaryName) {
        final String resource = "/" + binaryName.replace('.', '/') + ".class";
        try (InputStream in = DcrBatchSaveSynthProcessorTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "找不到类资源: " + resource);
            return in.readAllBytes();
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }
}
