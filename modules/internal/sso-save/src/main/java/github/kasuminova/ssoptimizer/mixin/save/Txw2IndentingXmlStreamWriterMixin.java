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

    @Overwrite(remap = false)
    public void writeStartDocument() throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeStartDocument(ssoptimizer$delegateWriter());
    }

    @Overwrite(remap = false)
    public void writeStartDocument(final String version) throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeStartDocument(ssoptimizer$delegateWriter(), version);
    }

    @Overwrite(remap = false)
    public void writeStartDocument(final String encoding, final String version) throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeStartDocument(ssoptimizer$delegateWriter(), encoding, version);
    }

    @Overwrite(remap = false)
    public void writeStartElement(final String localName) throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeStartElement(ssoptimizer$delegateWriter(), localName);
    }

    @Overwrite(remap = false)
    public void writeStartElement(final String namespaceUri, final String localName) throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeStartElement(ssoptimizer$delegateWriter(), namespaceUri, localName);
    }

    @Overwrite(remap = false)
    public void writeStartElement(final String prefix,
                                  final String localName,
                                  final String namespaceUri) throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeStartElement(ssoptimizer$delegateWriter(), prefix, localName, namespaceUri);
    }

    @Overwrite(remap = false)
    public void writeEmptyElement(final String namespaceUri, final String localName) throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeEmptyElement(ssoptimizer$delegateWriter(), namespaceUri, localName);
    }

    @Overwrite(remap = false)
    public void writeEmptyElement(final String prefix,
                                  final String localName,
                                  final String namespaceUri) throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeEmptyElement(ssoptimizer$delegateWriter(), prefix, localName, namespaceUri);
    }

    @Overwrite(remap = false)
    public void writeEmptyElement(final String localName) throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeEmptyElement(ssoptimizer$delegateWriter(), localName);
    }

    @Overwrite(remap = false)
    public void writeEndElement() throws XMLStreamException {
        Txw2CompactXmlWriterHelper.writeEndElement(ssoptimizer$delegateWriter());
    }
}