package github.kasuminova.ssoptimizer.common.save;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * txw2 XML 写入紧凑模式辅助类。
 * <p>
 * 职责：为 {@code com.sun.xml.txw2.output.IndentingXMLStreamWriter} 提供无缩进、无额外换行的直写路径，
 * 直接把节点开始/结束与文档开始调用转发给底层 {@link XMLStreamWriter}。<br>
 * 设计动机：热点显示 txw2 的缩进包装器在保存阶段会为每个节点维护状态栈、深度和换行缩进字符串，
 * 这些操作会显著放大 XML 序列化的常数开销；而存档文件本身并不依赖这些格式化空白。<br>
 * 注：该辅助类只改变输出格式的人类可读性，不改变 XML 结构和标签顺序。
 */
public final class Txw2CompactXmlWriterHelper {
    /**
     * 禁用 txw2 批量队列写入器的系统属性。
     */
    public static final String DISABLE_QUEUED_WRITER_PROPERTY = "ssoptimizer.disable.txw2.queuedwriter";

    /**
     * 自定义 txw2 批量队列事件容量的系统属性。
     */
    public static final String QUEUED_WRITER_QUEUE_CAPACITY_PROPERTY = "ssoptimizer.txw2.queuedwriter.queuecapacity";

    /**
     * 自定义 txw2 批量队列批大小的系统属性。
     */
    public static final String QUEUED_WRITER_BATCH_SIZE_PROPERTY = "ssoptimizer.txw2.queuedwriter.batchsize";

    private static final int DEFAULT_QUEUE_CAPACITY = 16_384;
    private static final int DEFAULT_BATCH_SIZE = 256;

    private Txw2CompactXmlWriterHelper() {
    }

    /**
     * 按当前系统属性将底层写入器升级为批量队列写入器。
     *
     * @param writer 原始 XML 写入器
     * @return 若启用则返回包装后的队列写入器；否则返回原始写入器
     */
    public static XMLStreamWriter optimizeWriter(final XMLStreamWriter writer) {
        if (Boolean.getBoolean(DISABLE_QUEUED_WRITER_PROPERTY) || writer instanceof QueuedXmlStreamWriter) {
            return writer;
        }

        return new QueuedXmlStreamWriter(
                writer,
                intProperty(QUEUED_WRITER_QUEUE_CAPACITY_PROPERTY, DEFAULT_QUEUE_CAPACITY, 256),
                intProperty(QUEUED_WRITER_BATCH_SIZE_PROPERTY, DEFAULT_BATCH_SIZE, 16)
        );
    }

    /**
     * 写入 XML 文档头。
     *
     * @param writer 底层 XML 写入器
     * @throws XMLStreamException 写入失败时抛出
     */
    public static void writeStartDocument(final XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartDocument();
    }

    /**
     * 写入带版本号的 XML 文档头。
     *
     * @param writer  底层 XML 写入器
     * @param version XML 版本字符串
     * @throws XMLStreamException 写入失败时抛出
     */
    public static void writeStartDocument(final XMLStreamWriter writer,
                                          final String version) throws XMLStreamException {
        writer.writeStartDocument(version);
    }

    /**
     * 写入带编码与版本号的 XML 文档头。
     *
     * @param writer   底层 XML 写入器
     * @param encoding 编码名称
     * @param version  XML 版本字符串
     * @throws XMLStreamException 写入失败时抛出
     */
    public static void writeStartDocument(final XMLStreamWriter writer,
                                          final String encoding,
                                          final String version) throws XMLStreamException {
        writer.writeStartDocument(encoding, version);
    }

    /**
     * 写入开始标签。
     *
     * @param writer    底层 XML 写入器
     * @param localName 标签名
     * @throws XMLStreamException 写入失败时抛出
     */
    public static void writeStartElement(final XMLStreamWriter writer,
                                         final String localName) throws XMLStreamException {
        writer.writeStartElement(localName);
    }

    /**
     * 写入带命名空间的开始标签。
     *
     * @param writer        底层 XML 写入器
     * @param namespaceUri  命名空间 URI
     * @param localName     标签名
     * @throws XMLStreamException 写入失败时抛出
     */
    public static void writeStartElement(final XMLStreamWriter writer,
                                         final String namespaceUri,
                                         final String localName) throws XMLStreamException {
        writer.writeStartElement(namespaceUri, localName);
    }

    /**
     * 写入带前缀和命名空间的开始标签。
     *
     * @param writer        底层 XML 写入器
     * @param prefix        命名空间前缀
     * @param localName     标签名
     * @param namespaceUri  命名空间 URI
     * @throws XMLStreamException 写入失败时抛出
     */
    public static void writeStartElement(final XMLStreamWriter writer,
                                         final String prefix,
                                         final String localName,
                                         final String namespaceUri) throws XMLStreamException {
        writer.writeStartElement(prefix, localName, namespaceUri);
    }

    /**
     * 写入空标签。
     *
     * @param writer        底层 XML 写入器
     * @param namespaceUri  命名空间 URI
     * @param localName     标签名
     * @throws XMLStreamException 写入失败时抛出
     */
    public static void writeEmptyElement(final XMLStreamWriter writer,
                                         final String namespaceUri,
                                         final String localName) throws XMLStreamException {
        writer.writeEmptyElement(namespaceUri, localName);
    }

    /**
     * 写入带前缀和命名空间的空标签。
     *
     * @param writer        底层 XML 写入器
     * @param prefix        命名空间前缀
     * @param localName     标签名
     * @param namespaceUri  命名空间 URI
     * @throws XMLStreamException 写入失败时抛出
     */
    public static void writeEmptyElement(final XMLStreamWriter writer,
                                         final String prefix,
                                         final String localName,
                                         final String namespaceUri) throws XMLStreamException {
        writer.writeEmptyElement(prefix, localName, namespaceUri);
    }

    /**
     * 写入本地空标签。
     *
     * @param writer    底层 XML 写入器
     * @param localName 标签名
     * @throws XMLStreamException 写入失败时抛出
     */
    public static void writeEmptyElement(final XMLStreamWriter writer,
                                         final String localName) throws XMLStreamException {
        writer.writeEmptyElement(localName);
    }

    /**
     * 写入结束标签。
     *
     * @param writer 底层 XML 写入器
     * @throws XMLStreamException 写入失败时抛出
     */
    public static void writeEndElement(final XMLStreamWriter writer) throws XMLStreamException {
        writer.writeEndElement();
    }

    private static int intProperty(final String propertyName,
                                   final int defaultValue,
                                   final int minValue) {
        final String raw = System.getProperty(propertyName);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }

        try {
            return Math.max(minValue, Integer.parseInt(raw));
        } catch (final NumberFormatException ignored) {
            return defaultValue;
        }
    }

}