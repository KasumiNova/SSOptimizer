package github.kasuminova.ssoptimizer.common.save;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Txw2CompactXmlWriterHelperTest {
    @Test
    void writesCompactXmlWithoutIndentationArtifacts() throws Exception {
        StringWriter output = new StringWriter();
        XMLStreamWriter writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);

        Txw2CompactXmlWriterHelper.writeStartDocument(writer);
        Txw2CompactXmlWriterHelper.writeStartElement(writer, "root");
        Txw2CompactXmlWriterHelper.writeEmptyElement(writer, "child");
        Txw2CompactXmlWriterHelper.writeEndElement(writer);
        writer.writeEndDocument();
        writer.flush();

        String xml = output.toString();
        assertTrue(xml.contains("<root><child"), "compact writer should not insert pretty-print indentation between root and child");
        assertTrue(!xml.contains("\n<child") && !xml.contains("\n  <child"),
                "compact writer output should not contain indentation newlines before child nodes");
    }
}