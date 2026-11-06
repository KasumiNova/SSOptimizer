package github.kasuminova.ssoptimizer.common.save;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

public final class Txw2CompactXmlWriterHelper {
    public static final String DISABLE_QUEUED_WRITER_PROPERTY = "ssoptimizer.disable.txw2.queuedwriter";

    public static final String QUEUED_WRITER_QUEUE_CAPACITY_PROPERTY = "ssoptimizer.txw2.queuedwriter.queuecapacity";

    public static final String QUEUED_WRITER_BATCH_SIZE_PROPERTY = "ssoptimizer.txw2.queuedwriter.batchsize";

    private static final int DEFAULT_QUEUE_CAPACITY = 16_384;
    private static final int DEFAULT_BATCH_SIZE = 256;

    private Txw2CompactXmlWriterHelper() {
    }

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

    public static void writeStartDocument(final XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartDocument();
    }

    public static void writeStartDocument(final XMLStreamWriter writer,
                                          final String version) throws XMLStreamException {
        writer.writeStartDocument(version);
    }

    public static void writeStartDocument(final XMLStreamWriter writer,
                                          final String encoding,
                                          final String version) throws XMLStreamException {
        writer.writeStartDocument(encoding, version);
    }

    public static void writeStartElement(final XMLStreamWriter writer,
                                         final String localName) throws XMLStreamException {
        writer.writeStartElement(localName);
    }

    public static void writeStartElement(final XMLStreamWriter writer,
                                         final String namespaceUri,
                                         final String localName) throws XMLStreamException {
        writer.writeStartElement(namespaceUri, localName);
    }

    public static void writeStartElement(final XMLStreamWriter writer,
                                         final String prefix,
                                         final String localName,
                                         final String namespaceUri) throws XMLStreamException {
        writer.writeStartElement(prefix, localName, namespaceUri);
    }

    public static void writeEmptyElement(final XMLStreamWriter writer,
                                         final String namespaceUri,
                                         final String localName) throws XMLStreamException {
        writer.writeEmptyElement(namespaceUri, localName);
    }

    public static void writeEmptyElement(final XMLStreamWriter writer,
                                         final String prefix,
                                         final String localName,
                                         final String namespaceUri) throws XMLStreamException {
        writer.writeEmptyElement(prefix, localName, namespaceUri);
    }

    public static void writeEmptyElement(final XMLStreamWriter writer,
                                         final String localName) throws XMLStreamException {
        writer.writeEmptyElement(localName);
    }

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