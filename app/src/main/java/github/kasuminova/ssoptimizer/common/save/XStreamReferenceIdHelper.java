package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import com.thoughtworks.xstream.mapper.Mapper;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * XStream 引用 ID 生成辅助类。
 * <p>
 * 职责：为 {@code ReferenceByIdMarshaller} 的默认 {@code SequenceGenerator} 提供更紧凑的引用 ID 生成路径，
 * 避免每次都走十进制 {@link String#valueOf(int)} 的热点转换。<br>
 * 设计动机：更新后的热点报告显示 {@code SequenceGenerator.next()} 在大存档保存时稳定占据前排 CPU；
 * 这些 ID 只在单个 XML 文档内部做引用匹配，不要求保持十进制格式，因此可以改为更短的 base36 文本。<br>
 * 兼容性策略：仅当 marshaller 实际持有 XStream 默认的 {@code SequenceGenerator} 时才启用优化；
 * 若上游传入自定义 {@code IDGenerator}，则完全回退到原始生成逻辑，不改变外部行为。
 */
public final class XStreamReferenceIdHelper {
    private static final int PRECOMPUTED_REFERENCE_ID_LIMIT = 1 << 16;
    private static final char[] DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final String[] PRECOMPUTED_REFERENCE_IDS = buildPrecomputedReferenceIds();

    private static final Class<?> SEQUENCE_GENERATOR_CLASS = resolveSequenceGeneratorClass();
    private static final VarHandle SEQUENCE_COUNTER_HANDLE = resolveSequenceCounterHandle();
    private static final Class<?> TREE_MARSHALLER_CLASS = resolveTreeMarshallerClass();
    private static final VarHandle TREE_MARSHALLER_MAPPER_HANDLE = resolveTreeMarshallerMapperHandle();
    private static final VarHandle TREE_MARSHALLER_WRITER_HANDLE = resolveTreeMarshallerWriterHandle();

    private XStreamReferenceIdHelper() {
    }

    /**
     * 判断是否可以对当前 ID 生成器启用优化路径。
     *
     * @param generator XStream 引用 ID 生成器
     * @return 若生成器是默认 {@code SequenceGenerator} 且可读取计数器，则返回 {@code true}
     */
    public static boolean supportsOptimizedIds(final Object generator) {
        return generator != null
                && SEQUENCE_GENERATOR_CLASS != null
                && SEQUENCE_COUNTER_HANDLE != null
                && generator.getClass() == SEQUENCE_GENERATOR_CLASS;
    }

    /**
     * 读取默认 {@code SequenceGenerator} 的当前计数器。
     *
     * @param generator 默认 XStream 顺序 ID 生成器
     * @return 当前下一个待分配的引用 ID 序号
     */
    public static int readSequenceCounter(final Object generator) {
        if (!supportsOptimizedIds(generator)) {
            throw new IllegalArgumentException("Unsupported XStream ID generator: " + generator);
        }
        return (int) SEQUENCE_COUNTER_HANDLE.get(generator);
    }

    /**
     * 创建新的紧凑引用 ID。
     *
     * @param value 顺序整数值
     * @return 与原始 XStream 兼容的紧凑字符串 ID
     */
    public static String nextReferenceId(final int value) {
        return toCompactString(value);
    }

    /**
     * 解析当前 marshaller 对系统属性 {@code id} 的别名。
     *
     * @param marshaller XStream 引用 marshaller
     * @return 当前 mapper 配置下的 `id` 属性别名；若不可用则返回 {@code null}
     */
    public static String resolveIdAttributeAlias(final Object marshaller) {
        final Mapper mapper = readMapper(marshaller);
        return mapper == null ? null : mapper.aliasForSystemAttribute("id");
    }

    /**
     * 直接向当前 marshaller 绑定的 writer 写入引用 ID 属性。
     *
     * @param marshaller XStream 引用 marshaller
     * @param attributeName 属性名
     * @param item 属性值对象
     */
    public static void writeReferenceIdAttribute(final Object marshaller,
                                                 final String attributeName,
                                                 final Object item) {
        final HierarchicalStreamWriter writer = readWriter(marshaller);
        if (writer == null || attributeName == null) {
            return;
        }
        writer.addAttribute(attributeName, item instanceof String string ? string : item.toString());
    }

    static String toCompactString(final int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Reference id must be non-negative: " + value);
        }
        if (value < PRECOMPUTED_REFERENCE_ID_LIMIT) {
            return PRECOMPUTED_REFERENCE_IDS[value];
        }
        return toCompactStringUncached(value);
    }

    static String toCompactStringUncached(final int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Reference id must be non-negative: " + value);
        }
        if (value == 0) {
            return "0";
        }

        final char[] buffer = new char[7];
        int current = value;
        int cursor = buffer.length;
        while (current != 0) {
            final int digit = current % DIGITS.length;
            buffer[--cursor] = DIGITS[digit];
            current /= DIGITS.length;
        }
        return new String(buffer, cursor, buffer.length - cursor);
    }

    private static String[] buildPrecomputedReferenceIds() {
        final String[] cache = new String[PRECOMPUTED_REFERENCE_ID_LIMIT];
        cache[0] = "0";
        for (int value = 1; value < cache.length; value++) {
            cache[value] = toCompactStringUncached(value);
        }
        return cache;
    }

    private static Class<?> resolveSequenceGeneratorClass() {
        try {
            return Class.forName("com.thoughtworks.xstream.core.SequenceGenerator");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Class<?> resolveTreeMarshallerClass() {
        try {
            return Class.forName("com.thoughtworks.xstream.core.TreeMarshaller");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static VarHandle resolveSequenceCounterHandle() {
        final Class<?> generatorClass = SEQUENCE_GENERATOR_CLASS;
        if (generatorClass == null) {
            return null;
        }

        try {
            return MethodHandles.privateLookupIn(generatorClass, MethodHandles.lookup())
                    .findVarHandle(generatorClass, "counter", int.class);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static VarHandle resolveTreeMarshallerMapperHandle() {
        final Class<?> treeMarshallerClass = TREE_MARSHALLER_CLASS;
        if (treeMarshallerClass == null) {
            return null;
        }

        try {
            return MethodHandles.privateLookupIn(treeMarshallerClass, MethodHandles.lookup())
                    .findVarHandle(treeMarshallerClass, "mapper", Mapper.class);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static VarHandle resolveTreeMarshallerWriterHandle() {
        final Class<?> treeMarshallerClass = TREE_MARSHALLER_CLASS;
        if (treeMarshallerClass == null) {
            return null;
        }

        try {
            return MethodHandles.privateLookupIn(treeMarshallerClass, MethodHandles.lookup())
                    .findVarHandle(treeMarshallerClass, "writer", HierarchicalStreamWriter.class);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Mapper readMapper(final Object marshaller) {
        final VarHandle handle = TREE_MARSHALLER_MAPPER_HANDLE;
        if (handle == null || marshaller == null) {
            return null;
        }
        return (Mapper) handle.get(marshaller);
    }

    private static HierarchicalStreamWriter readWriter(final Object marshaller) {
        final VarHandle handle = TREE_MARSHALLER_WRITER_HANDLE;
        if (handle == null || marshaller == null) {
            return null;
        }
        return (HierarchicalStreamWriter) handle.get(marshaller);
    }
}