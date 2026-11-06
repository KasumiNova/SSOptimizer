package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.asm.render.RenderThreadRedirector;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;

/** 为注入的 findClass 提供类资源读取与渲染重定向，避免在织入字节码中展开 IO 和异常处理。 */
public final class ShipMasteryLoaderSupport {

    private ShipMasteryLoaderSupport() {
    }

    /** 返回从 loader 自身 classpath 读取并经渲染重定向的类字节；资源不存在时返回 null，读取失败时抛出 ClassNotFoundException。 */
    public static byte[] loadTransformedBytes(final URLClassLoader loader, final String binaryName)
            throws ClassNotFoundException {
        final URL resource = loader.findResource(binaryName.replace('.', '/') + ".class");
        if (resource == null) {
            return null;
        }
        try (InputStream in = resource.openStream()) {
            return RenderThreadRedirector.redirect(binaryName, in.readAllBytes());
        } catch (IOException e) {
            throw new ClassNotFoundException(
                    "[SSOptimizer] 读取 " + binaryName + " 类字节失败: " + resource, e);
        }
    }
}
