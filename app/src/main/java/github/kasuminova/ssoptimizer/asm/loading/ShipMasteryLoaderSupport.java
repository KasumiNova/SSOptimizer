package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.asm.render.RenderThreadRedirector;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Ship Mastery System 自建类加载器（{@code ModPlugin$ReflectionEnabledClassLoader}）
 * 的运行时支撑：为 {@link ShipMasteryReflectionLoaderProcessor} 注入的
 * {@code findClass} 覆盖提供「从 loader 自身 classpath 读类字节 → 渲染线程 GL 重定向」
 * 的静态入口。
 * <p>
 * 之所以把 IO 与重定向收进本类而非直接织入字节码：注入点（findClass 覆盖）只应保留
 * 最小指令序列（读字节、definePackage、defineClass），资源读取与异常包装在这里用
 * 正常 Java 表达，避免大段手写 ASM。
 */
public final class ShipMasteryLoaderSupport {

    private ShipMasteryLoaderSupport() {
    }

    /**
     * 从 loader 自身（不委派父加载器）读取类字节，并经
     * {@link RenderThreadRedirector#redirect(String, byte[])} 重定向
     * （非分离模式 / 无 GL 引用时零开销原样返回）。
     *
     * @param loader     目标 URLClassLoader（ReflectionEnabledClassLoader 实例）
     * @param binaryName 点号二进制类名（内部类含 {@code $}）
     * @return 重定向后的类字节；loader 自身 classpath 无此类时返回 {@code null}
     *         （调用方据此回退 {@code super.findClass}，保持原语义）
     * @throws ClassNotFoundException 资源存在但读取失败——IO 异常不静默吞掉，
     *         包装为 ClassNotFoundException 上抛（与 findClass 签名兼容）
     */
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
