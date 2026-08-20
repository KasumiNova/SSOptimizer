package github.kasuminova.ssoptimizer.api.font;

import java.io.IOException;
import java.io.InputStream;

/**
 * 原版字体资源覆盖查询：loading 域解码贴图/字体资源时判定并读取 TTF 字体覆盖。
 * <p>
 * 动机：font 域的 TTF 字体包会覆盖原版字体资源（.fnt/.png），loading 域的
 * 资源解码/预载路径需要感知覆盖关系（命中覆盖的资源必须改从字体包读取并跳过
 * 转换缓存），但 loading 不允许直接依赖 font——跨域行为调用经本接口。
 * <p>
 * 实现由 font 域提供（桥接 OriginalGameFontOverrides），在 coremod 装配期经
 * {@code ServiceRegistry} 注册；语义上允许缺省（未注册=无字体覆盖，如单元测试/
 * 离线工具场景），调用点经 {@code ServiceRegistry.getOrNull} 显式判空。
 */
public interface FontResourceOverride {

    /**
     * 规范化资源路径（统一分隔符/大小写等与覆盖表对齐的形式）。
     *
     * @param resourcePath 原始资源路径
     * @return 规范化后的路径
     */
    String normalize(String resourcePath);

    /**
     * @return TTF 字体覆盖总开关是否启用
     */
    boolean isEnabled();

    /**
     * @param normalizedPath 经 {@link #normalize} 规范化后的资源路径
     * @return 该路径是否被字体包覆盖
     */
    boolean isOverriddenPath(String normalizedPath);

    /**
     * 打开覆盖资源的流。
     *
     * @param resourcePath 原始资源路径
     * @return 覆盖资源流；未覆盖或读取失败返回 {@code null}
     * @throws IOException 读取过程中的 IO 错误
     */
    InputStream openStream(String resourcePath) throws IOException;
}
