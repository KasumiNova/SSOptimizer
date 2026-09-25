package github.kasuminova.ssoptimizer.asm.render;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GL bridge 全量镜像的覆盖率断言（见 docs/design/gl-bridge-full-mirror.md）。
 * <p>
 * 对 {@link RenderThreadRedirector#MIRRORED_CLASS_NAMES} 的每个类：
 * 用 ASM 解析 classpath 上的真实 {@code org/lwjgl/opengl/<Name>.class}（含其
 * org/lwjgl/opengl 包内超类链，如 ARBVertexBufferObject → ARBBufferObject），
 * 收集全部 public static 方法的 name+desc（desc 经对象身份类型替换——
 * GLSync/Drawable/SharedDrawable 在桥世界是另一类型）；同法收集 bridge 类
 * （含 Gen 生成基类超类链）的方法集。断言 real ⊆ bridge，白名单为空——
 * 缺什么补什么，不允许豁免（漏镜像 = 分离模式下无 context 线程直触真实 GL）。
 */
class BridgeMirrorCoverageTest {
    private static final String LWJGL_PREFIX = "org/lwjgl/opengl/";
    private static final String BRIDGE_PREFIX = "github/kasuminova/ssoptimizer/bridge/opengl/";
    /** 与 RenderThreadRedirector.TYPE_REMAP 同款的对象身份类型替换。 */
    private static final Map<String, String> TYPE_REMAP = Map.of(
            "Lorg/lwjgl/opengl/GLSync;", "Lgithub/kasuminova/ssoptimizer/bridge/opengl/GLSync;",
            "Lorg/lwjgl/opengl/Drawable;", "Lgithub/kasuminova/ssoptimizer/bridge/opengl/Drawable;",
            "Lorg/lwjgl/opengl/SharedDrawable;", "Lgithub/kasuminova/ssoptimizer/bridge/opengl/SharedDrawable;");

    @Test
    void realLwjglSurfaceIsFullyMirrored() {
        StringBuilder failures = new StringBuilder();
        for (String simpleName : RenderThreadRedirector.MIRRORED_CLASS_NAMES) {
            Set<String> real = collectPublicStatic(LWJGL_PREFIX + simpleName, LWJGL_PREFIX, true);
            Set<String> bridge = collectPublicStatic(BRIDGE_PREFIX + simpleName, BRIDGE_PREFIX, false);
            Set<String> missing = new TreeSet<>(real);
            missing.removeAll(bridge);
            if (!missing.isEmpty()) {
                failures.append(simpleName).append(" 缺失 ").append(missing.size()).append(" 个方法:\n");
                missing.forEach(m -> failures.append("    ").append(m).append('\n'));
            }
        }
        assertTrue(failures.isEmpty(),
                "bridge 镜像覆盖面缺口（真实 lwjgl public static 方法未镜像）:\n" + failures);
    }

    /**
     * 收集类的静态方法 name+desc 集合，沿 superName 链递归（链限定在给定前缀包内）。
     *
     * @param internalName 起点类内部名
     * @param chainPrefix  超类链延伸的包前缀（真实侧 org/lwjgl/opengl/，桥侧 bridge 包）
     * @param publicOnly   真实侧只收 public static（javac 可见面）；桥侧收全部声明
     *                     （生成物与手写均为 public static，宽收不影响超集判定）
     */
    private static Set<String> collectPublicStatic(String internalName, String chainPrefix,
                                                   boolean publicOnly) {
        Set<String> methods = new TreeSet<>();
        String current = internalName;
        while (current != null && current.startsWith(chainPrefix)) {
            ClassReader reader = readClass(current);
            final boolean filter = publicOnly;
            final String[] superName = new String[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, null) {
                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superNameArg, String[] interfaces) {
                    superName[0] = superNameArg;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    if (!name.startsWith("<")
                            && (access & Opcodes.ACC_STATIC) != 0
                            && (!filter || (access & Opcodes.ACC_PUBLIC) != 0)) {
                        methods.add(name + remapDescriptor(desc));
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            current = superName[0];
        }
        return methods;
    }

    private static ClassReader readClass(String internalName) {
        String resource = internalName + ".class";
        ClassLoader loader = BridgeMirrorCoverageTest.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("classpath 资源缺失: " + resource);
            }
            return new ClassReader(in);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 classpath 资源失败: " + resource, e);
        }
    }

    private static String remapDescriptor(String desc) {
        String result = desc;
        for (Map.Entry<String, String> entry : TYPE_REMAP.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
