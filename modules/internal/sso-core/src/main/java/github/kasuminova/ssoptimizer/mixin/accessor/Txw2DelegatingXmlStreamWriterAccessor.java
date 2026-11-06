package github.kasuminova.ssoptimizer.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import javax.xml.stream.XMLStreamWriter;

@Mixin(targets = "com.sun.xml.txw2.output.DelegatingXMLStreamWriter")
public interface Txw2DelegatingXmlStreamWriterAccessor {
    @Accessor("writer")
    XMLStreamWriter ssoptimizer$getWriter();

    @Mutable
    @Accessor("writer")
    void ssoptimizer$setWriter(XMLStreamWriter writer);
}