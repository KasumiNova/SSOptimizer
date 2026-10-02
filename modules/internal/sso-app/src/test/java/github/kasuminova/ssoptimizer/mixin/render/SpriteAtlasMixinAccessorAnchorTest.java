package github.kasuminova.ssoptimizer.mixin.render;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link SpriteAtlasMixin} UV 访问器覆写锚点核验。
 * <p>
 * 8 个 {@code @Overwrite}（setTexX/setTexY/setTexWidth/setTexHeight 与对应
 * getter）的正确性取决于 named jar 的 {@code com/fs/graphics/Sprite} 存在
 * 同名同签名方法（Mixin 覆写目标缺失会在运行期抛 InvalidMixinException）。
 * 用 ASM 解析测试 classpath 上 named jar 的真实字节码核验，
 * 方法缺失/签名漂移在构建期暴露而非运行时失败。
 */
class SpriteAtlasMixinAccessorAnchorTest {

    private static final String TARGET_CLASS = "com/fs/graphics/Sprite";

    private static final String[][] ACCESSORS = {
            {"setTexX", "(F)V"},
            {"setTexY", "(F)V"},
            {"setTexWidth", "(F)V"},
            {"setTexHeight", "(F)V"},
            {"getTexX", "()F"},
            {"getTexY", "()F"},
            {"getTexWidth", "()F"},
            {"getTexHeight", "()F"},
    };

    @Test
    void spriteDeclaresAllOverwrittenUvAccessors() throws IOException {
        final ClassNode node = readClasspathClass(TARGET_CLASS);
        for (final String[] accessor : ACCESSORS) {
            assertNotNull(findMethod(node, accessor[0], accessor[1]),
                    "Sprite." + accessor[0] + accessor[1] + " 必须存在（@Overwrite 目标）");
        }
    }

    private static MethodNode findMethod(final ClassNode node, final String name, final String desc) {
        for (final MethodNode method : node.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) {
                return method;
            }
        }
        return null;
    }

    private static ClassNode readClasspathClass(final String internalName) throws IOException {
        final String resource = internalName + ".class";
        try (InputStream in = SpriteAtlasMixinAccessorAnchorTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "测试 classpath 必须包含 " + resource);
            final ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
}
