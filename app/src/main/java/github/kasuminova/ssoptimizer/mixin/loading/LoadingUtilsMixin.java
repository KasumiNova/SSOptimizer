package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.loading.LoadingJsonCommentStripper;
import github.kasuminova.ssoptimizer.common.loading.LoadingTextResourceReader;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.json.JSONException;
import org.json.JSONObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.io.IOException;
import java.io.InputStream;

/**
 * LoadingUtils 文本读取路径的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.starfarer.loading.LoadingUtils#readText(InputStream)}
 * 与 {@code #parseJSONStrippingComments(String, String)}<br>
 * 注入动机：原版热点路径逐字符构建字符串，面对大量文本资源时 CPU 与分配开销较高。<br>
 * 注入效果：文本读取整体替换为 {@link LoadingTextResourceReader#read} 的批量 UTF-8 读取；
 * JSON 注释剥离替换为 {@link LoadingJsonCommentStripper#parse} 的单遍扫描实现。
 */
@Mixin(targets = GameClassNames.LOADING_UTILS_DOTTED)
public abstract class LoadingUtilsMixin {

    /**
     * 将文本资源读取委托给批量 UTF-8 读取 helper。
     *
     * @param inputStream 资源输入流
     * @return 资源文本内容
     * @throws IOException 读取失败时抛出
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换静态 readText 方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    public static String readText(InputStream inputStream) throws IOException {
        return LoadingTextResourceReader.read(inputStream);
    }

    /**
     * 将 JSON 注释剥离 + 解析委托给单遍扫描 helper。
     *
     * @param sourceName 来源名称（异常信息前缀）
     * @param text 原始 JSON 文本
     * @return 解析后的 JSONObject
     * @throws JSONException 解析失败时抛出
     * @author KasumiNova
     * @reason 原版逐字符 StringBuffer append 在大文本上开销高，替换为等义的快速单遍实现。
     */
    @Overwrite(remap = false)
    public static JSONObject parseJSONStrippingComments(String sourceName, String text) throws JSONException {
        return LoadingJsonCommentStripper.parse(sourceName, text);
    }
}
