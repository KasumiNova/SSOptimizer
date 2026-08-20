package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.io.path.Path;

import java.util.Arrays;

/**
 * XStream {@code PathTracker} 路径构建辅助类。
 * <p>
 * 职责：维护已格式化的路径片段缓存，并在需要生成 {@link Path} 时使用数组复制直接构造路径对象，
 * 避免 XStream 原实现中 {@code getPath()} 反复调用 {@code peekElement()} 产生的 map 查询与字符串拼接。<br>
 * 兼容性策略：路径片段格式严格保持为 XStream 原语义（同名兄弟节点索引从 {@code [2]} 开始追加），
 * 只改变缓存时机，不改变对外可见结果。
 */
public final class XStreamPathTrackerHelper {
    private XStreamPathTrackerHelper() {
    }

    /**
     * 创建指定容量的已格式化路径片段数组。
     *
     * @param capacity 初始容量
     * @return 新数组
     */
    public static String[] createFormattedPathStack(final int capacity) {
        return new String[Math.max(1, capacity)];
    }

    /**
     * 扩容已格式化路径片段数组。
     *
     * @param original    原数组
     * @param newCapacity 新容量
     * @return 扩容后的数组
     */
    public static String[] resizeFormattedPathStack(final String[] original, final int newCapacity) {
        return Arrays.copyOf(original, Math.max(1, newCapacity));
    }

    /**
     * 按 XStream 原语义格式化路径片段。
     *
     * @param elementName 节点名
     * @param index       当前同名兄弟序号
     * @return 格式化后的路径片段
     */
    public static String formatElement(final String elementName, final int index) {
        if (index <= 1) {
            return elementName;
        }
        return elementName + '[' + index + ']';
    }

    /**
     * 用当前深度内的已格式化路径片段构造 {@link Path}。
     *
     * @param formattedPathStack 已格式化路径片段缓存
     * @param depth              当前深度
     * @return 新构造的路径对象
     */
    public static Path buildPath(final String[] formattedPathStack, final int depth) {
        final String[] chunks = new String[depth + 1];
        chunks[0] = "";
        if (depth > 0) {
            System.arraycopy(formattedPathStack, 0, chunks, 1, depth);
        }
        return new Path(chunks);
    }
}