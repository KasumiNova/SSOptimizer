package github.kasuminova.ssoptimizer.modopt.dcr;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

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
