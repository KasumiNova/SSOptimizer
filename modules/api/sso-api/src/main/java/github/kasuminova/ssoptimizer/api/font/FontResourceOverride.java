package github.kasuminova.ssoptimizer.api.font;

import java.io.IOException;
import java.io.InputStream;

/**
 * 查询并读取 TTF 字体包对原版字体描述和贴图资源的覆盖，使资源加载模块无需依赖字体模块。
 * <p>
 * 方法可由资源加载线程并发调用，实现须保证覆盖表查询与延迟初始化的线程安全。
 * 未注册 = 无字体覆盖，调用方继续读取原始资源。
 */
public interface FontResourceOverride {

    /**
     * 将资源路径转换为覆盖表使用的形式，仅移除路径开头的一个 {@code /}。
     *
     * @param resourcePath 原始资源路径，可为 {@code null}
     * @return 规范化路径；{@code null} 输入返回空字符串
     */
    String normalize(String resourcePath);

    /**
     * 查询字体覆盖总开关，不触发资源生成。
     *
     * @return 启用时返回 {@code true}；关闭时 {@link #openStream(String)} 返回 {@code null}
     */
    boolean isEnabled();

    /**
     * 判定规范化路径是否属于覆盖表，不检查总开关，也不生成资源。
     *
     * @param normalizedPath 经 {@link #normalize(String)} 处理的资源路径
     * @return 属于覆盖表时返回 {@code true}；空白路径返回 {@code false}
     */
    boolean isOverriddenPath(String normalizedPath);

    /**
     * 打开原始资源路径对应的覆盖资源流，调用方负责关闭返回的流。
     * 首次命中覆盖路径可能触发字体资源的延迟生成。
     *
     * @param resourcePath 原始资源路径
     * @return 覆盖资源流；功能关闭、路径未覆盖或覆盖资源未生成时返回 {@code null}
     * @throws IOException 打开覆盖资源时发生 I/O 错误
     */
    InputStream openStream(String resourcePath) throws IOException;
}
