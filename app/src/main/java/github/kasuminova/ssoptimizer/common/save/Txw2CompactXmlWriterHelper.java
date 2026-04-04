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
    private Txw2CompactXmlWriterHelper() {
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
}