package github.kasuminova.ssoptimizer.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * 运行期字节码视图测试 fixture。
 * <p>
 * coremod 化后 NanoForge 在类加载前完成 obf→named 全量 remap，ASM 处理器的工作视图
 * 就是 named 游戏 jar 中的字节码本身；named jar（SourceSector 发布）在测试 classpath 上，
 * 故直接按资源路径读取即可得到与运行期一致的 named 视图。第三方类（janino、txw2 等）
 * 同样从测试 classpath 读取。
 */
final class RuntimeViewFixtures {

    private RuntimeViewFixtures() {
    }

    /**
     * 读取运行期 named 视图类字节码（ASM 处理器工作视图）。
     *
     * @param internalName named 内部名
     * @return 类字节码；classpath 上不存在时返回 {@code null}
     */
    static byte[] readRuntimeNamedBytes(String internalName) {
        String resourcePath = internalName + ".class";
        try (InputStream input = RuntimeViewFixtures.class.getClassLoader().getResourceAsStream(resourcePath)) {
            return input != null ? input.readAllBytes() : null;
        } catch (IOException exception) {
            throw new UncheckedIOException("读取 classpath 资源失败: " + resourcePath, exception);
        }
    }
}
