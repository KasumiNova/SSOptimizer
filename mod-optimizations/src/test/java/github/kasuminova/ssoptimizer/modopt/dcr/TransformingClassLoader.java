package github.kasuminova.ssoptimizer.modopt.dcr;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 测试用织入类加载器：对指定（二进制名 → 处理器）的类读取原始字节、应用处理器并 {@code defineClass}，
 * 其余类委派父加载器。属全局规范允许的「动态加载 / 注入框架」用途，仅在此承载 defineClass 管线，
 * 使转换后的字节码能被 JVM 实际加载并校验（栈帧合法性 / 链接正确性 的真实证明）。
 */
final class TransformingClassLoader extends ClassLoader {

    private final Map<String, AsmClassProcessor> targets;

    TransformingClassLoader(final ClassLoader parent, final Map<String, AsmClassProcessor> targets) {
        super(parent);
        this.targets = new HashMap<>(targets);
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                final AsmClassProcessor processor = targets.get(name);
                if (processor == null) {
                    return super.loadClass(name, resolve);
                }
                loaded = defineTransformed(name, processor);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private Class<?> defineTransformed(final String name, final AsmClassProcessor processor)
            throws ClassNotFoundException {
        final String resource = name.replace('.', '/') + ".class";
        try (InputStream in = getParent().getResourceAsStream(resource)) {
            if (in == null) {
                throw new ClassNotFoundException("找不到原始类字节: " + resource);
            }
            final byte[] original = in.readAllBytes();
            final byte[] transformed = processor.process(original);
            final byte[] bytes = transformed != null ? transformed : original;
            return defineClass(name, bytes, 0, bytes.length);
        } catch (final IOException e) {
            throw new ClassNotFoundException(name, e);
        }
    }
}
