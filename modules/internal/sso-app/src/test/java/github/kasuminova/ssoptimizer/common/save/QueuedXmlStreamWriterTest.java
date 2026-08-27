package github.kasuminova.ssoptimizer.common.save;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.io.StringWriter;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void workerThreadIsVirtual() throws Exception {
        StringWriter output = new StringWriter();
        QueuedXmlStreamWriter writer = new QueuedXmlStreamWriter(
                XMLOutputFactory.newFactory().createXMLStreamWriter(output),
                64,
                16
        );

        // Wave 3 迁移验收：后台写线程必须为虚拟线程
        assertTrue(extractWorkerThread(writer).isVirtual(),
                "queued writer worker thread should be a virtual thread");

        writer.writeStartDocument();
        writer.writeStartElement("root");
        writer.writeEndElement();
        writer.writeEndDocument();
        writer.close();
    }

    @Test
    void workerFailurePropagatesAtFlushBarrier() throws Exception {
        // 底层输出立即失败的 Writer：后台线程执行首个批次时失败，
        // 失败必须经 awaitBarrier/checkFailure 在前台屏障点抛出
        final Writer failingOutput = new Writer() {
            @Override
            public void write(final char[] cbuf, final int off, final int len) throws IOException {
                throw new IOException("simulated backend failure");
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        QueuedXmlStreamWriter writer = new QueuedXmlStreamWriter(
                XMLOutputFactory.newFactory().createXMLStreamWriter(failingOutput),
                64,
                16
        );

        writer.writeStartDocument();

        // awaitBarrier 语义：后台失败必须在下一个前台屏障点（flush）抛出，不得静默
        assertThrows(XMLStreamException.class, writer::flush,
                "后台写线程失败必须在 flush 屏障处向前台抛出");
        // 失败状态粘滞：后续 close 也必须抛出同一失败而非假装成功
        assertThrows(XMLStreamException.class, writer::close,
                "失败后的 close 必须继续抛出失败而非静默关闭");
    }

    private static Thread extractWorkerThread(final QueuedXmlStreamWriter writer) throws Exception {
        final Field workerField = QueuedXmlStreamWriter.class.getDeclaredField("workerThread");
        workerField.setAccessible(true);
        return (Thread) workerField.get(writer);
    }
}