package github.kasuminova.ssoptimizer.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

final class RuntimeViewFixtures {

    private RuntimeViewFixtures() {
    }

    static byte[] readRuntimeNamedBytes(String internalName) {
        String resourcePath = internalName + ".class";
        try (InputStream input = RuntimeViewFixtures.class.getClassLoader().getResourceAsStream(resourcePath)) {
            return input != null ? input.readAllBytes() : null;
        } catch (IOException exception) {
            throw new UncheckedIOException("读取 classpath 资源失败: " + resourcePath, exception);
        }
    }
}
