package github.kasuminova.ssoptimizer.common.loading;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * LoadingUtils JSON 解析前置管线的快速实现。
 * <p>
 * 原版 {@code parseJSONStrippingComments} 逐字符向 StringBuffer append 剥离 # 注释，
 * 面对数 MB 的 CSV/JSON 文本时分配与 CPU 开销较高；这里用单遍扫描 + 预分配
 * StringBuilder 复刻完全一致的剥离语义（含原版在换行处重置字符串状态的细节），
 * 最后直接产出 JSONObject。
 */
public final class LoadingJsonCommentStripper {

    private LoadingJsonCommentStripper() {
    }

    /**
     * 剥离注释并解析 JSON。
     *
     * @param sourceName 来源名称（用于异常信息前缀）
     * @param text 原始文本
     * @return 解析后的 JSONObject
     * @throws JSONException 解析失败时抛出，信息前缀与原版一致
     */
    public static JSONObject parse(final String sourceName, final String text) throws JSONException {
        try {
            return new JSONObject(strip(text));
        } catch (final JSONException e) {
            throw new JSONException(sourceName + "\n" + e.getMessage());
        }
    }

    /**
     * 单遍剥离 # 注释。语义与原版逐字符循环完全一致：
     * 双引号切换字符串状态；换行重置注释与字符串状态（\n 保留、\r 丢弃）；
     * 字符串外的 # 进入注释状态直至行尾。
     */
    static String strip(final String text) {
        final StringBuilder result = new StringBuilder(text.length());
        boolean inComment = false;
        boolean inString = false;

        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '"') {
                inString = !inString;
            }

            if (c == '\n' || c == '\r') {
                inComment = false;
                inString = false;
                if (c == '\n') {
                    result.append('\n');
                }
            } else if (c == '#' && !inString) {
                inComment = true;
            } else if (!inComment) {
                result.append(c);
            }
        }

        return result.toString();
    }
}
