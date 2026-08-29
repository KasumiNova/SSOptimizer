package github.kasuminova.ssoptimizer.mixin.campaign;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ListenerManagerSyncMixin} 的字节码锚点核验。
 * <p>
 * 线程安全化的正确性前提是「{@code ListenerManager} 的全部 API 方法都被
 * synchronized 覆写覆盖，不残留任何未互斥的并发入口」。此处对测试 classpath 上
 * named jar 的真实 {@code ListenerManager}/{@code ListenerManagerAPI} 逐一核验：
 * API 接口声明的每个方法必须恰好对应 Mixin 中一个带 {@code ACC_SYNCHRONIZED}
 * 的 {@code @Overwrite} 方法。
 */
class ListenerManagerSyncAnchorTest {

    @Test
    void everyApiMethodHasSynchronizedOverwrite() throws IOException {
        final ClassNode api = readClasspathClass("com/fs/starfarer/api/campaign/listeners/ListenerManagerAPI");
        final ClassNode target = readClasspathClass("com/fs/starfarer/campaign/ListenerManager");
        final ClassNode mixin = readClasspathClass(
                "github/kasuminova/ssoptimizer/mixin/campaign/ListenerManagerSyncMixin");

        final Set<String> overwritten = new HashSet<>();
        for (final MethodNode method : mixin.methods) {
            if (hasOverwriteAnnotation(method)) {
                overwritten.add(method.name + method.desc);
                assertTrue((method.access & Opcodes.ACC_SYNCHRONIZED) != 0,
                        "@Overwrite 方法必须 synchronized: " + method.name + method.desc);
            }
        }

        final Set<String> apiMethods = new HashSet<>();
        for (final MethodNode method : api.methods) {
            apiMethods.add(method.name + method.desc);
            assertNotNull(findMethod(target, method.name, method.desc),
                    "ListenerManager 必须实现 API 方法: " + method.name + method.desc);
        }

        assertEquals(apiMethods, overwritten,
                "ListenerManagerAPI 全部方法必须恰好被 synchronized 覆写覆盖（无未互斥入口）");
    }

    private static boolean hasOverwriteAnnotation(final MethodNode method) {
        if (method.visibleAnnotations == null) {
            return false;
        }
        return method.visibleAnnotations.stream()
                .anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Overwrite;"));
    }

    private static ClassNode readClasspathClass(final String slashName) throws IOException {
        try (InputStream in = ListenerManagerSyncAnchorTest.class.getClassLoader()
                .getResourceAsStream(slashName + ".class")) {
            assertNotNull(in, "测试 classpath 必须包含类: " + slashName);
            final ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static MethodNode findMethod(final ClassNode node, final String name, final String desc) {
        for (final MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        return null;
    }
}
