package github.kasuminova.ssoptimizer.mixin.save;

import github.kasuminova.ssoptimizer.common.save.Txw2CompactXmlWriterHelper;
import github.kasuminova.ssoptimizer.mixin.accessor.Txw2DelegatingXmlStreamWriterAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * txw2 缩进 XML 写入器的紧凑模式 Mixin。
 * <p>
 * 注入目标：{@code com.sun.xml.txw2.output.IndentingXMLStreamWriter}<br>
 * 注入动机：热点显示该类在保存阶段会为每个节点维护缩进状态栈、深度与换行输出；这些格式化工作对存档正确性没有帮助，
 * 却会显著增加 CPU 开销。<br>
 * 注入效果：覆盖文档头、开始标签、空标签和结束标签写出逻辑，直接委托到底层 {@link XMLStreamWriter}，
 * 跳过 txw2 的缩进/换行维护。
 */
@Mixin(targets = "com.sun.xml.txw2.output.IndentingXMLStreamWriter")
public abstract class Txw2IndentingXmlStreamWriterMixin {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ssoptimizer$optimizeDelegateWriter(final XMLStreamWriter writer,
                                                    final CallbackInfo callbackInfo) {
        ((Txw2DelegatingXmlStreamWriterAccessor) (Object) this).ssoptimizer$setWriter(
                Txw2CompactXmlWriterHelper.optimizeWriter(writer)
        );
    }

    @Unique
    private XMLStreamWriter ssoptimizer$delegateWriter() {
        return ((Txw2DelegatingXmlStreamWriterAccessor) (Object) this).ssoptimizer$getWriter();
    }

    /**
     * 写入 XML 文档头。
     *
     * @author GitHub Copilot
     * @reason 去掉 txw2 在文档头后追加换行的格式化成本，保留结构语义不变。
     */
    @Overwrite(remap = false)
    public void writeStartDocument() throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeStartDocument(ssoptimizer$delegateWriter());
    }

    /**
     * 写入带版本号的 XML 文档头。
     *
     * @param version XML 版本字符串
     * @author GitHub Copilot
     * @reason 去掉 txw2 在文档头后追加换行的格式化成本，保留结构语义不变。
     */
    @Overwrite(remap = false)
    public void writeStartDocument(final String version) throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeStartDocument(ssoptimizer$delegateWriter(), version);
    }

    /**
     * 写入带编码与版本号的 XML 文档头。
     *
     * @param encoding 编码名称
     * @param version  XML 版本字符串
     * @author GitHub Copilot
     * @reason 去掉 txw2 在文档头后追加换行的格式化成本，保留结构语义不变。
     */
    @Overwrite(remap = false)
    public void writeStartDocument(final String encoding, final String version) throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeStartDocument(ssoptimizer$delegateWriter(), encoding, version);
    }

    /**
     * 写入开始标签。
     *
     * @param localName 标签名
     * @author GitHub Copilot
     * @reason 跳过 txw2 的缩进状态栈与换行逻辑，直接写出节点开始标签。
     */
    @Overwrite(remap = false)
    public void writeStartElement(final String localName) throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeStartElement(ssoptimizer$delegateWriter(), localName);
    }

    /**
     * 写入带命名空间的开始标签。
     *
     * @param namespaceUri 命名空间 URI
     * @param localName    标签名
     * @author GitHub Copilot
     * @reason 跳过 txw2 的缩进状态栈与换行逻辑，直接写出节点开始标签。
     */
    @Overwrite(remap = false)
    public void writeStartElement(final String namespaceUri, final String localName) throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeStartElement(ssoptimizer$delegateWriter(), namespaceUri, localName);
    }

    /**
     * 写入带前缀和命名空间的开始标签。
     *
     * @param prefix       命名空间前缀
     * @param localName    标签名
     * @param namespaceUri 命名空间 URI
     * @author GitHub Copilot
     * @reason 跳过 txw2 的缩进状态栈与换行逻辑，直接写出节点开始标签。
     */
    @Overwrite(remap = false)
    public void writeStartElement(final String prefix,
                                  final String localName,
                                  final String namespaceUri) throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeStartElement(ssoptimizer$delegateWriter(), prefix, localName, namespaceUri);
    }

    /**
     * 写入空标签。
     *
     * @param namespaceUri 命名空间 URI
     * @param localName    标签名
     * @author GitHub Copilot
     * @reason 跳过 txw2 的空标签缩进与换行处理，直接写出结构。
     */
    @Overwrite(remap = false)
    public void writeEmptyElement(final String namespaceUri, final String localName) throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeEmptyElement(ssoptimizer$delegateWriter(), namespaceUri, localName);
    }

    /**
     * 写入带前缀和命名空间的空标签。
     *
     * @param prefix       命名空间前缀
     * @param localName    标签名
     * @param namespaceUri 命名空间 URI
     * @author GitHub Copilot
     * @reason 跳过 txw2 的空标签缩进与换行处理，直接写出结构。
     */
    @Overwrite(remap = false)
    public void writeEmptyElement(final String prefix,
                                  final String localName,
                                  final String namespaceUri) throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeEmptyElement(ssoptimizer$delegateWriter(), prefix, localName, namespaceUri);
    }

    /**
     * 写入本地空标签。
     *
     * @param localName 标签名
     * @author GitHub Copilot
     * @reason 跳过 txw2 的空标签缩进与换行处理，直接写出结构。
     */
    @Overwrite(remap = false)
    public void writeEmptyElement(final String localName) throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeEmptyElement(ssoptimizer$delegateWriter(), localName);
    }

    /**
     * 写入结束标签。
     *
     * @author GitHub Copilot
     * @reason 跳过 txw2 的结束标签缩进与换行处理，减少保存阶段的格式化成本。
     */
    @Overwrite(remap = false)
    public void writeEndElement() throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeEndElement(ssoptimizer$delegateWriter());
    }
}