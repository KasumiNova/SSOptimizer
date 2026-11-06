package github.kasuminova.ssoptimizer.mixin.render;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    private static final String[] HEAL_TARGET = {"renderWithCorners", "(FFFFFFFF)V"};

    @Test
    void spriteDeclaresAllOverwrittenUvAccessors() throws IOException {
        final ClassNode node = readClasspathClass(TARGET_CLASS);
        for (final String[] accessor : ACCESSORS) {
            assertNotNull(findMethod(node, accessor[0], accessor[1]),
                    "Sprite." + accessor[0] + accessor[1] + " 必须存在（@Overwrite 目标）");
        }
    }

    @Test
    void spriteDeclaresRenderWithCornersHealTarget() throws IOException {
        final ClassNode node = readClasspathClass(TARGET_CLASS);
        assertNotNull(findMethod(node, HEAL_TARGET[0], HEAL_TARGET[1]),
                "Sprite." + HEAL_TARGET[0] + HEAL_TARGET[1] + " 必须存在（惰性治愈 @Inject HEAD 目标）");
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
