package github.kasuminova.ssoptimizer.common.save;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.lang.reflect.Field;
import java.io.StringWriter;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueuedXmlStreamWriterTest {
    @AfterEach
    void clearQueuedWriterProperties() {
        System.clearProperty(Txw2CompactXmlWriterHelper.DISABLE_QUEUED_WRITER_PROPERTY);
        System.clearProperty(Txw2CompactXmlWriterHelper.QUEUED_WRITER_QUEUE_CAPACITY_PROPERTY);
        System.clearProperty(Txw2CompactXmlWriterHelper.QUEUED_WRITER_BATCH_SIZE_PROPERTY);
    }

    @Test
    void writesQueuedXmlInOriginalOrder() throws Exception {
        StringWriter output = new StringWriter();
        QueuedXmlStreamWriter writer = new QueuedXmlStreamWriter(
                XMLOutputFactory.newFactory().createXMLStreamWriter(output),
                64,
                16
        );

        writer.writeStartDocument();
        writer.writeStartElement("root");
        writer.writeAttribute("id", "1");
        writer.writeCharacters("alpha");
        writer.writeComment("note");
        writer.writeCData("beta");
        writer.writeEndElement();
        writer.writeEndDocument();
        writer.flush();
        writer.close();

        String xml = output.toString();
        assertTrue(xml.contains("<root id=\"1\">alpha<!--note--><![CDATA[beta]]></root>"),
                "queued writer should preserve XML event ordering");
    }

    @Test
    void closeDrainsPendingWrites() throws Exception {
        StringWriter output = new StringWriter();
        QueuedXmlStreamWriter writer = new QueuedXmlStreamWriter(
                XMLOutputFactory.newFactory().createXMLStreamWriter(output),
                64,
                16
        );

        writer.writeStartDocument();
        writer.writeStartElement("root");
        writer.writeEmptyElement("child");
        writer.writeEndElement();
        writer.writeEndDocument();
        writer.close();

        String xml = output.toString();
        assertTrue(xml.contains("<root><child"),
                "closing queued writer should flush pending XML structure");
    }

        @Test
        void flushAfterEndDocumentAutoClosesWorkerThread() throws Exception {
        StringWriter output = new StringWriter();
        QueuedXmlStreamWriter writer = new QueuedXmlStreamWriter(
            XMLOutputFactory.newFactory().createXMLStreamWriter(output),
            64,
            16
        );

        writer.writeStartDocument();
        writer.writeStartElement("root");
        writer.writeCharacters("done");
        writer.writeEndElement();
        writer.writeEndDocument();
        writer.flush();
        writer.close();

        String xml = output.toString();
        assertTrue(xml.contains("<root>done</root>"),
            "flush after endDocument should still drain the queued XML content before auto-closing");
        assertTrue(awaitWorkerShutdown(writer, Duration.ofSeconds(2)),
            "flush after endDocument should automatically terminate the queued writer worker thread");
        }

    @Test
    void helperCanReturnOriginalWriterWhenDisabled() throws Exception {
        XMLStreamWriter original = XMLOutputFactory.newFactory().createXMLStreamWriter(new StringWriter());
        System.setProperty(Txw2CompactXmlWriterHelper.DISABLE_QUEUED_WRITER_PROPERTY, "true");

        XMLStreamWriter optimized = Txw2CompactXmlWriterHelper.optimizeWriter(original);

        assertSame(original, optimized, "disable property should bypass queued writer wrapping");
        original.close();
    }

    @Test
    void helperWrapsWriterWhenEnabled() throws Exception {
        XMLStreamWriter original = XMLOutputFactory.newFactory().createXMLStreamWriter(new StringWriter());
        System.setProperty(Txw2CompactXmlWriterHelper.QUEUED_WRITER_QUEUE_CAPACITY_PROPERTY, "64");
        System.setProperty(Txw2CompactXmlWriterHelper.QUEUED_WRITER_BATCH_SIZE_PROPERTY, "16");

        XMLStreamWriter optimized = Txw2CompactXmlWriterHelper.optimizeWriter(original);

        assertTrue(optimized instanceof QueuedXmlStreamWriter,
                "enabled helper should wrap XML writer with queued implementation");
        optimized.close();
    }

    private static boolean awaitWorkerShutdown(final QueuedXmlStreamWriter writer,
                                               final Duration timeout) throws Exception {
        final Thread workerThread = extractWorkerThread(writer);
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (workerThread.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        return !workerThread.isAlive();
    }

    private static Thread extractWorkerThread(final QueuedXmlStreamWriter writer) throws Exception {
        final Field workerField = QueuedXmlStreamWriter.class.getDeclaredField("workerThread");
        workerField.setAccessible(true);
        return (Thread) workerField.get(writer);
    }
}