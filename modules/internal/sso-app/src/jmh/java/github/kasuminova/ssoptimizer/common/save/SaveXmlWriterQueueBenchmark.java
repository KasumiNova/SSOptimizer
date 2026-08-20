package github.kasuminova.ssoptimizer.common.save;

import com.sun.xml.txw2.output.IndentingXMLStreamWriter;
import org.jctools.queues.SpscArrayQueue;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 基于真实存档 XML 语料验证写出路径开销的 JMH 基准。
 * <p>
 * 基准目标不是测试 XStream 整体序列化，而是验证“XML 写出阶段”本身：
 * </p>
 * <ul>
 *     <li>直接写入普通 StAX Writer</li>
 *     <li>直接写入 txw2 {@link IndentingXMLStreamWriter}</li>
 *     <li>使用 JCTools SPSC 队列，把解析线程产出的写指令交给独立 Writer 线程执行</li>
 * </ul>
 * <p>
 * 默认语料目录优先读取系统属性 {@code ssoptimizer.saveCorpusDir}，
 * 未提供时回退到当前开发机的 {@code /mnt/windows_data/Games/Starsector098-linux/saves}。
 * </p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class SaveXmlWriterQueueBenchmark {
    private static final String SAVE_CORPUS_DIR_PROPERTY = "ssoptimizer.saveCorpusDir";
    private static final String XML_MAX_ELEMENT_DEPTH_PROPERTY = "jdk.xml.maxElementDepth";
    private static final Path DEFAULT_SAVE_CORPUS_DIR = Path.of("/mnt/windows_data/Games/Starsector098-linux/saves");
    private static final int MAX_INITIAL_OUTPUT_BUFFER = 8 * 1024 * 1024;
    private static final int MIN_INITIAL_OUTPUT_BUFFER = 64 * 1024;

    /**
     * 基准：流式解析真实 XML，并直接写入紧凑 StAX Writer。
     *
     * @param state 基准状态
     * @param blackhole JMH 黑洞
     * @throws Exception 当 XML 解析或写出失败时抛出
     */
    @Benchmark
    public void directCompact(final CorpusState state, final Blackhole blackhole) throws Exception {
        BenchmarkSummary summary = replayDirect(state, false);
        consume(summary, blackhole);
    }

    /**
     * 基准：流式解析真实 XML，并写入 txw2 缩进 Writer。
     *
     * @param state 基准状态
     * @param blackhole JMH 黑洞
     * @throws Exception 当 XML 解析或写出失败时抛出
     */
    @Benchmark
    public void txw2Indenting(final CorpusState state, final Blackhole blackhole) throws Exception {
        BenchmarkSummary summary = replayDirect(state, true);
        consume(summary, blackhole);
    }

    /**
     * 基准：流式解析真实 XML，并通过 JCTools SPSC 队列把写指令移交给独立 Writer 线程。
     *
     * @param state 基准状态
     * @param blackhole JMH 黑洞
     * @throws Exception 当 XML 解析或写出失败时抛出
     */
    @Benchmark
    public void queuedSpscCompact(final CorpusState state, final Blackhole blackhole) throws Exception {
        BenchmarkSummary summary = state.worker.replay(state.documents, state.inputFactory);
        consume(summary, blackhole);
    }

    /**
     * 基准：流式解析真实 XML，并通过可复用批量 opcode 队列交给独立 Writer 线程。
     *
     * @param state 基准状态
     * @param blackhole JMH 黑洞
     * @throws Exception 当 XML 解析或写出失败时抛出
     */
    @Benchmark
    public void queuedSpscBatchedCompact(final CorpusState state, final Blackhole blackhole) throws Exception {
        BenchmarkSummary summary = state.batchedWorker.replay(state.documents, state.inputFactory);
        consume(summary, blackhole);
    }

    private static void consume(final BenchmarkSummary summary, final Blackhole blackhole) {
        blackhole.consume(summary.totalBytes());
        blackhole.consume(summary.documentCount());
        blackhole.consume(summary.sampleChecksum());
    }

    private static BenchmarkSummary replayDirect(final CorpusState state,
                                                 final boolean indenting) throws IOException, XMLStreamException {
        long totalBytes = 0L;
        int sampleChecksum = 1;

        for (byte[] document : state.documents) {
            ByteArrayOutputStream out = createOutputBuffer(document.length);
            XMLStreamWriter writer = state.outputFactory.createXMLStreamWriter(out, StandardCharsets.UTF_8.name());
            XMLStreamWriter targetWriter = indenting ? new IndentingXMLStreamWriter(writer) : writer;

            try {
                pumpDocument(document, state.inputFactory, new DirectXmlEventSink(targetWriter));
                targetWriter.flush();
            } finally {
                targetWriter.close();
            }

            byte[] encoded = out.toByteArray();
            totalBytes += encoded.length;
            sampleChecksum = foldSample(sampleChecksum, encoded);
        }

        return new BenchmarkSummary(totalBytes, state.documents.size(), sampleChecksum);
    }

    private static ByteArrayOutputStream createOutputBuffer(final int inputBytes) {
        int initialCapacity = Math.max(MIN_INITIAL_OUTPUT_BUFFER, Math.min(inputBytes, MAX_INITIAL_OUTPUT_BUFFER));
        return new ByteArrayOutputStream(initialCapacity);
    }

    private static int foldSample(final int checksum, final byte[] encoded) {
        if (encoded.length == 0) {
            return 31 * checksum + 1;
        }

        int first = encoded[0] & 0xFF;
        int middle = encoded[encoded.length / 2] & 0xFF;
        int last = encoded[encoded.length - 1] & 0xFF;

        int folded = checksum;
        folded = 31 * folded + encoded.length;
        folded = 31 * folded + first;
        folded = 31 * folded + middle;
        folded = 31 * folded + last;
        return folded;
    }

    private static void pumpDocument(final byte[] xmlBytes,
                                     final XMLInputFactory inputFactory,
                                     final XmlEventSink sink) throws XMLStreamException {
        XMLStreamReader reader = inputFactory.createXMLStreamReader(new ByteArrayInputStream(xmlBytes), StandardCharsets.UTF_8.name());
        try {
            while (true) {
                emitCurrentEvent(reader, sink);
                if (!reader.hasNext()) {
                    break;
                }
                reader.next();
            }
        } finally {
            reader.close();
        }
    }

    private static void emitCurrentEvent(final XMLStreamReader reader,
                                         final XmlEventSink sink) throws XMLStreamException {
        switch (reader.getEventType()) {
            case XMLStreamConstants.START_DOCUMENT -> sink.startDocument(emptyToNull(reader.getVersion()));
            case XMLStreamConstants.START_ELEMENT -> {
                sink.startElement(
                        emptyToNull(reader.getPrefix()),
                        reader.getLocalName(),
                        emptyToNull(reader.getNamespaceURI())
                );

                for (int i = 0, count = reader.getNamespaceCount(); i < count; i++) {
                    sink.namespace(
                            emptyToNull(reader.getNamespacePrefix(i)),
                            emptyToNull(reader.getNamespaceURI(i))
                    );
                }

                for (int i = 0, count = reader.getAttributeCount(); i < count; i++) {
                    sink.attribute(
                            emptyToNull(reader.getAttributePrefix(i)),
                            emptyToNull(reader.getAttributeNamespace(i)),
                            reader.getAttributeLocalName(i),
                            reader.getAttributeValue(i)
                    );
                }
            }
            case XMLStreamConstants.CHARACTERS, XMLStreamConstants.SPACE -> sink.characters(reader.getText());
            case XMLStreamConstants.CDATA -> sink.cdata(reader.getText());
            case XMLStreamConstants.COMMENT -> sink.comment(reader.getText());
            case XMLStreamConstants.PROCESSING_INSTRUCTION -> sink.processingInstruction(
                    reader.getPITarget(),
                    emptyToNull(reader.getPIData())
            );
            case XMLStreamConstants.DTD -> sink.dtd(reader.getText());
            case XMLStreamConstants.END_ELEMENT -> sink.endElement();
            case XMLStreamConstants.END_DOCUMENT -> sink.endDocument();
            default -> {
                // 其他事件（如实体引用）在当前存档 XML 里不会成为主要热点，这里显式忽略。
            }
        }
    }

    private static String emptyToNull(final String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    /**
     * 单线程基准状态：缓存语料、XML 工厂与队列工作线程。
     */
    @State(Scope.Thread)
    public static class CorpusState {
        /**
         * 语料配置：
         * <ul>
         *     <li>{@code descriptor-16}：选取 16 个真实 {@code descriptor.xml}</li>
         *     <li>{@code campaign-medium}：选取一个 64-96 MiB 的 {@code campaign.xml}</li>
         *     <li>{@code campaign-large}：选取一个 128-160 MiB 的 {@code campaign.xml}</li>
         * </ul>
         */
        @Param({"descriptor-16", "campaign-medium"})
        public String corpus;

        /**
         * JCTools SPSC 环形队列容量。
         */
        @Param({"16384"})
        public int queueCapacity;

        /**
         * 批量 opcode 队列每批次可容纳的事件数。
         */
        @Param({"256"})
        public int batchSize;

        XMLInputFactory   inputFactory;
        XMLOutputFactory  outputFactory;
        List<byte[]>      documents;
        List<Path>        sourceFiles;
        QueuedCopyWorker  worker;
        BatchedQueuedCopyWorker batchedWorker;
        String            previousXmlMaxElementDepth;

        /**
         * 加载真实 XML 语料并启动队列写线程。
         *
         * @throws IOException 当语料目录或 XML 文件无法读取时抛出
         */
        @Setup(Level.Trial)
        public void setup() throws IOException {
            previousXmlMaxElementDepth = System.getProperty(XML_MAX_ELEMENT_DEPTH_PROPERTY);
            System.setProperty(XML_MAX_ELEMENT_DEPTH_PROPERTY, "0");

            Path corpusDir = resolveCorpusDir();
            sourceFiles = selectCorpus(corpusDir, corpus);
            documents = new ArrayList<>(sourceFiles.size());
            for (Path file : sourceFiles) {
                documents.add(Files.readAllBytes(file));
            }

            inputFactory = XMLInputFactory.newFactory();
            outputFactory = XMLOutputFactory.newFactory();
            worker = new QueuedCopyWorker(queueCapacity);
            batchedWorker = new BatchedQueuedCopyWorker(queueCapacity, batchSize);
        }

        /**
         * 关闭队列写线程。
         */
        @TearDown(Level.Trial)
        public void tearDown() {
            if (worker != null) {
                worker.close();
            }
            if (batchedWorker != null) {
                batchedWorker.close();
            }

            if (previousXmlMaxElementDepth == null) {
                System.clearProperty(XML_MAX_ELEMENT_DEPTH_PROPERTY);
            } else {
                System.setProperty(XML_MAX_ELEMENT_DEPTH_PROPERTY, previousXmlMaxElementDepth);
            }
        }
    }

    private static Path resolveCorpusDir() {
        String override = System.getProperty(SAVE_CORPUS_DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path overridePath = Path.of(override).toAbsolutePath();
            if (Files.isDirectory(overridePath)) {
                return overridePath;
            }
            throw new IllegalStateException("save 语料目录不存在: " + overridePath);
        }

        if (Files.isDirectory(DEFAULT_SAVE_CORPUS_DIR)) {
            return DEFAULT_SAVE_CORPUS_DIR;
        }

        throw new IllegalStateException(
                "未找到 save 语料目录，请通过 -D" + SAVE_CORPUS_DIR_PROPERTY + "=/path/to/saves 显式指定"
        );
    }

    private static List<Path> selectCorpus(final Path corpusDir,
                                           final String corpusProfile) throws IOException {
        return switch (corpusProfile) {
            case "descriptor-16" -> selectLargestFiles(corpusDir, "descriptor.xml", 16);
            case "campaign-medium" -> selectFilesNearRange(corpusDir, "campaign.xml", 1, 64L << 20, 96L << 20);
            case "campaign-large" -> selectFilesNearRange(corpusDir, "campaign.xml", 1, 128L << 20, 160L << 20);
            default -> throw new IllegalArgumentException("未知语料配置: " + corpusProfile);
        };
    }

    private static List<Path> selectLargestFiles(final Path corpusDir,
                                                 final String fileName,
                                                 final int limit) throws IOException {
        try (var stream = Files.walk(corpusDir, 2)) {
            List<Path> selected = stream.filter(Files::isRegularFile)
                                        .filter(path -> path.getFileName().toString().equals(fileName))
                                        .sorted(Comparator.comparingLong(SaveXmlWriterQueueBenchmark::sizeOf).reversed()
                                                          .thenComparing(Path::toString))
                                        .limit(limit)
                                        .toList();
            if (selected.isEmpty()) {
                throw new IllegalStateException("未在语料目录中找到 " + fileName);
            }
            return selected;
        }
    }

    private static List<Path> selectFilesNearRange(final Path corpusDir,
                                                   final String fileName,
                                                   final int limit,
                                                   final long minBytes,
                                                   final long maxBytes) throws IOException {
        long midpoint = (minBytes + maxBytes) >>> 1;
        try (var stream = Files.walk(corpusDir, 2)) {
            List<Path> candidates = stream.filter(Files::isRegularFile)
                                          .filter(path -> path.getFileName().toString().equals(fileName))
                                          .sorted(Comparator.comparingLong((Path path) -> distanceToRange(sizeOf(path), minBytes, maxBytes))
                                                            .thenComparingLong(path -> Math.abs(sizeOf(path) - midpoint))
                                                            .thenComparing(Path::toString))
                                          .limit(limit)
                                          .toList();
            if (candidates.isEmpty()) {
                throw new IllegalStateException("未在语料目录中找到 " + fileName);
            }
            return candidates;
        }
    }

    private static long distanceToRange(final long value,
                                        final long minBytes,
                                        final long maxBytes) {
        if (value < minBytes) {
            return minBytes - value;
        }
        if (value > maxBytes) {
            return value - maxBytes;
        }
        return 0L;
    }

    private static long sizeOf(final Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取文件大小: " + path, e);
        }
    }

    private record BenchmarkSummary(long totalBytes, int documentCount, int sampleChecksum) {
    }

    /**
     * XML 事件接收器：用于复用“解析事件 -> 写出动作”的逻辑。
     */
    private interface XmlEventSink {
        void startDocument(String version) throws XMLStreamException;

        void startElement(String prefix, String localName, String namespaceUri) throws XMLStreamException;

        void namespace(String prefix, String namespaceUri) throws XMLStreamException;

        void attribute(String prefix, String namespaceUri, String localName, String value) throws XMLStreamException;

        void characters(String text) throws XMLStreamException;

        void cdata(String text) throws XMLStreamException;

        void comment(String text) throws XMLStreamException;

        void processingInstruction(String target, String data) throws XMLStreamException;

        void dtd(String text) throws XMLStreamException;

        void endElement() throws XMLStreamException;

        void endDocument() throws XMLStreamException;
    }

    /**
     * 直接写入 {@link XMLStreamWriter} 的事件接收器。
     */
    private static final class DirectXmlEventSink implements XmlEventSink {
        private final XMLStreamWriter writer;

        private DirectXmlEventSink(final XMLStreamWriter writer) {
            this.writer = writer;
        }

        @Override
        public void startDocument(final String version) throws XMLStreamException {
            if (version == null) {
                writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            } else {
                writer.writeStartDocument(StandardCharsets.UTF_8.name(), version);
            }
        }

        @Override
        public void startElement(final String prefix,
                                 final String localName,
                                 final String namespaceUri) throws XMLStreamException {
            if (namespaceUri != null && prefix != null) {
                writer.writeStartElement(prefix, localName, namespaceUri);
            } else if (namespaceUri != null) {
                writer.writeStartElement("", localName, namespaceUri);
            } else {
                writer.writeStartElement(localName);
            }
        }

        @Override
        public void namespace(final String prefix,
                              final String namespaceUri) throws XMLStreamException {
            if (prefix == null) {
                writer.writeDefaultNamespace(Objects.requireNonNullElse(namespaceUri, ""));
            } else {
                writer.writeNamespace(prefix, Objects.requireNonNullElse(namespaceUri, ""));
            }
        }

        @Override
        public void attribute(final String prefix,
                              final String namespaceUri,
                              final String localName,
                              final String value) throws XMLStreamException {
            if (namespaceUri != null && prefix != null) {
                writer.writeAttribute(prefix, namespaceUri, localName, value);
            } else if (namespaceUri != null) {
                writer.writeAttribute(namespaceUri, localName, value);
            } else {
                writer.writeAttribute(localName, value);
            }
        }

        @Override
        public void characters(final String text) throws XMLStreamException {
            writer.writeCharacters(text);
        }

        @Override
        public void cdata(final String text) throws XMLStreamException {
            writer.writeCData(text);
        }

        @Override
        public void comment(final String text) throws XMLStreamException {
            writer.writeComment(text);
        }

        @Override
        public void processingInstruction(final String target,
                                          final String data) throws XMLStreamException {
            if (data == null) {
                writer.writeProcessingInstruction(target);
            } else {
                writer.writeProcessingInstruction(target, data);
            }
        }

        @Override
        public void dtd(final String text) throws XMLStreamException {
            writer.writeDTD(text);
        }

        @Override
        public void endElement() throws XMLStreamException {
            writer.writeEndElement();
        }

        @Override
        public void endDocument() throws XMLStreamException {
            writer.writeEndDocument();
        }
    }

    /**
     * 使用 SPSC 队列桥接“解析线程 -> Writer 线程”的工作器。
     */
    private static final class QueuedCopyWorker implements AutoCloseable {
        private static final ControlCommand BEGIN_DOCUMENT = new ControlCommand(ControlType.BEGIN_DOCUMENT, 0L);
        private static final ControlCommand END_DOCUMENT = new ControlCommand(ControlType.END_DOCUMENT, 0L);
        private static final ControlCommand STOP = new ControlCommand(ControlType.STOP, 0L);

        private final SpscArrayQueue<XmlCommand> queue;
        private final XMLOutputFactory           outputFactory;
        private final Thread                     workerThread;

        private volatile boolean running = true;
        private volatile long completedBatchId = 0L;
        private volatile long totalBytes = 0L;
        private volatile int documentCount = 0;
        private volatile int sampleChecksum = 1;
        private volatile Throwable failure;
        private long nextBatchId = 1L;

        private QueuedCopyWorker(final int queueCapacity) {
            this.queue = new SpscArrayQueue<>(queueCapacity);
            this.outputFactory = XMLOutputFactory.newFactory();
            this.workerThread = Thread.ofPlatform()
                                      .name("ssoptimizer-jmh-xml-writer")
                                      .daemon(true)
                                      .start(this::runLoop);
        }

        private BenchmarkSummary replay(final List<byte[]> documents,
                                        final XMLInputFactory inputFactory) throws Exception {
            long batchId = nextBatchId++;
            failure = null;

            offer(new ControlCommand(ControlType.BEGIN_BATCH, batchId));
            for (byte[] document : documents) {
                offer(BEGIN_DOCUMENT);
                pumpDocument(document, inputFactory, new QueueXmlEventSink(this));
                offer(END_DOCUMENT);
            }
            offer(new ControlCommand(ControlType.END_BATCH, batchId));

            while (completedBatchId < batchId && failure == null) {
                Thread.onSpinWait();
            }

            if (failure != null) {
                throw propagateFailure(failure);
            }

            return new BenchmarkSummary(totalBytes, documentCount, sampleChecksum);
        }

        private Exception propagateFailure(final Throwable throwable) {
            if (throwable instanceof Exception exception) {
                return exception;
            }
            return new IllegalStateException("队列 Writer 线程执行失败", throwable);
        }

        private void offer(final XmlCommand command) {
            while (!queue.offer(command)) {
                if (failure != null) {
                    throw new IllegalStateException("队列 Writer 线程已失败", failure);
                }
                if (!workerThread.isAlive()) {
                    throw new IllegalStateException("队列 Writer 线程已终止");
                }
                Thread.onSpinWait();
            }
        }

        private void runLoop() {
            long currentBatchId = 0L;
            long currentTotalBytes = 0L;
            int currentDocumentCount = 0;
            int currentSampleChecksum = 1;
            ByteArrayOutputStream currentOutput = null;
            XMLStreamWriter currentWriter = null;

            while (running) {
                XmlCommand command = queue.relaxedPoll();
                if (command == null) {
                    Thread.onSpinWait();
                    continue;
                }

                try {
                    if (command instanceof ControlCommand control) {
                        switch (control.type()) {
                            case BEGIN_BATCH -> {
                                currentBatchId = control.batchId();
                                currentTotalBytes = 0L;
                                currentDocumentCount = 0;
                                currentSampleChecksum = 1;
                            }
                            case BEGIN_DOCUMENT -> {
                                currentOutput = createOutputBuffer(MAX_INITIAL_OUTPUT_BUFFER);
                                currentWriter = outputFactory.createXMLStreamWriter(currentOutput, StandardCharsets.UTF_8.name());
                            }
                            case END_DOCUMENT -> {
                                if (currentWriter != null) {
                                    currentWriter.flush();
                                    currentWriter.close();
                                }
                                if (currentOutput != null) {
                                    byte[] encoded = currentOutput.toByteArray();
                                    currentTotalBytes += encoded.length;
                                    currentSampleChecksum = foldSample(currentSampleChecksum, encoded);
                                    currentDocumentCount++;
                                }
                                currentWriter = null;
                                currentOutput = null;
                            }
                            case END_BATCH -> {
                                totalBytes = currentTotalBytes;
                                documentCount = currentDocumentCount;
                                sampleChecksum = currentSampleChecksum;
                                completedBatchId = control.batchId();
                            }
                            case STOP -> running = false;
                        }
                        continue;
                    }

                    if (currentWriter == null) {
                        throw new IllegalStateException("收到 XML 写指令时尚未开始文档");
                    }
                    command.apply(currentWriter);
                } catch (Throwable throwable) {
                    failure = throwable;
                    completedBatchId = Long.MAX_VALUE;
                    running = false;
                }
            }
        }

        @Override
        public void close() {
            if (workerThread.isAlive()) {
                offer(STOP);
            }
            try {
                workerThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 队列生产端事件接收器。
     */
    private static final class QueueXmlEventSink implements XmlEventSink {
        private final QueuedCopyWorker worker;

        private QueueXmlEventSink(final QueuedCopyWorker worker) {
            this.worker = worker;
        }

        @Override
        public void startDocument(final String version) {
            worker.offer(new StartDocumentCommand(version));
        }

        @Override
        public void startElement(final String prefix,
                                 final String localName,
                                 final String namespaceUri) {
            worker.offer(new StartElementCommand(prefix, localName, namespaceUri));
        }

        @Override
        public void namespace(final String prefix,
                              final String namespaceUri) {
            worker.offer(new NamespaceCommand(prefix, namespaceUri));
        }

        @Override
        public void attribute(final String prefix,
                              final String namespaceUri,
                              final String localName,
                              final String value) {
            worker.offer(new AttributeCommand(prefix, namespaceUri, localName, value));
        }

        @Override
        public void characters(final String text) {
            worker.offer(new CharactersCommand(text));
        }

        @Override
        public void cdata(final String text) {
            worker.offer(new CDataCommand(text));
        }

        @Override
        public void comment(final String text) {
            worker.offer(new CommentCommand(text));
        }

        @Override
        public void processingInstruction(final String target,
                                          final String data) {
            worker.offer(new ProcessingInstructionCommand(target, data));
        }

        @Override
        public void dtd(final String text) {
            worker.offer(new DtdCommand(text));
        }

        @Override
        public void endElement() {
            worker.offer(EndElementCommand.INSTANCE);
        }

        @Override
        public void endDocument() {
            worker.offer(EndDocumentCommand.INSTANCE);
        }
    }

    /**
     * 使用可复用批量缓冲区桥接“解析线程 -> Writer 线程”的工作器。
     */
    private static final class BatchedQueuedCopyWorker implements AutoCloseable {
        private static final byte OP_BEGIN_BATCH = 1;
        private static final byte OP_BEGIN_DOCUMENT = 2;
        private static final byte OP_START_DOCUMENT = 3;
        private static final byte OP_START_ELEMENT = 4;
        private static final byte OP_NAMESPACE = 5;
        private static final byte OP_ATTRIBUTE = 6;
        private static final byte OP_CHARACTERS = 7;
        private static final byte OP_CDATA = 8;
        private static final byte OP_COMMENT = 9;
        private static final byte OP_PROCESSING_INSTRUCTION = 10;
        private static final byte OP_DTD = 11;
        private static final byte OP_END_ELEMENT = 12;
        private static final byte OP_END_DOCUMENT = 13;
        private static final byte OP_FINISH_DOCUMENT = 14;
        private static final byte OP_END_BATCH = 15;
        private static final byte OP_STOP = 16;

        private final SpscArrayQueue<CommandBatch> freeQueue;
        private final SpscArrayQueue<CommandBatch> readyQueue;
        private final XMLOutputFactory             outputFactory;
        private final Thread                       workerThread;

        private volatile boolean running = true;
        private volatile long completedBatchId = 0L;
        private volatile long totalBytes = 0L;
        private volatile int documentCount = 0;
        private volatile int sampleChecksum = 1;
        private volatile Throwable failure;
        private long nextBatchId = 1L;

        private BatchedQueuedCopyWorker(final int queueCapacity,
                                        final int batchSize) {
            int effectiveBatchSize = Math.max(16, batchSize);
            int batchQueueCapacity = roundUpPowerOfTwo(Math.max(4, queueCapacity / effectiveBatchSize));

            this.freeQueue = new SpscArrayQueue<>(batchQueueCapacity);
            this.readyQueue = new SpscArrayQueue<>(batchQueueCapacity);
            this.outputFactory = XMLOutputFactory.newFactory();

            for (int i = 0; i < batchQueueCapacity; i++) {
                freeQueue.offer(new CommandBatch(effectiveBatchSize));
            }

            this.workerThread = Thread.ofPlatform()
                                      .name("ssoptimizer-jmh-xml-batched-writer")
                                      .daemon(true)
                                      .start(this::runLoop);
        }

        private BenchmarkSummary replay(final List<byte[]> documents,
                                        final XMLInputFactory inputFactory) throws Exception {
            long batchId = nextBatchId++;
            failure = null;

            BatchQueueXmlEventSink sink = new BatchQueueXmlEventSink(this);
            sink.control(OP_BEGIN_BATCH, batchId);
            for (byte[] document : documents) {
                sink.control(OP_BEGIN_DOCUMENT, 0L);
                pumpDocument(document, inputFactory, sink);
                sink.control(OP_FINISH_DOCUMENT, 0L);
            }
            sink.control(OP_END_BATCH, batchId);
            sink.finish();

            while (completedBatchId < batchId && failure == null) {
                Thread.onSpinWait();
            }

            if (failure != null) {
                throw propagateFailure(failure);
            }

            return new BenchmarkSummary(totalBytes, documentCount, sampleChecksum);
        }

        private Exception propagateFailure(final Throwable throwable) {
            if (throwable instanceof Exception exception) {
                return exception;
            }
            return new IllegalStateException("批量队列 Writer 线程执行失败", throwable);
        }

        private CommandBatch acquireBatch() {
            while (true) {
                CommandBatch batch = freeQueue.relaxedPoll();
                if (batch != null) {
                    batch.reset();
                    return batch;
                }

                if (failure != null) {
                    throw new IllegalStateException("批量队列 Writer 线程已失败", failure);
                }
                if (!workerThread.isAlive()) {
                    throw new IllegalStateException("批量队列 Writer 线程已终止");
                }
                Thread.onSpinWait();
            }
        }

        private void submitBatch(final CommandBatch batch) {
            while (!readyQueue.offer(batch)) {
                if (failure != null) {
                    throw new IllegalStateException("批量队列 Writer 线程已失败", failure);
                }
                if (!workerThread.isAlive()) {
                    throw new IllegalStateException("批量队列 Writer 线程已终止");
                }
                Thread.onSpinWait();
            }
        }

        private void recycleBatch(final CommandBatch batch) {
            batch.reset();
            while (!freeQueue.offer(batch)) {
                Thread.onSpinWait();
            }
        }

        private void runLoop() {
            long currentBatchId = 0L;
            long currentTotalBytes = 0L;
            int currentDocumentCount = 0;
            int currentSampleChecksum = 1;
            ByteArrayOutputStream currentOutput = null;
            XMLStreamWriter currentWriter = null;

            while (running) {
                CommandBatch batch = readyQueue.relaxedPoll();
                if (batch == null) {
                    Thread.onSpinWait();
                    continue;
                }

                try {
                    for (int i = 0; i < batch.size; i++) {
                        byte opcode = batch.opcodes[i];
                        switch (opcode) {
                            case OP_BEGIN_BATCH -> {
                                currentBatchId = batch.long0[i];
                                currentTotalBytes = 0L;
                                currentDocumentCount = 0;
                                currentSampleChecksum = 1;
                            }
                            case OP_BEGIN_DOCUMENT -> {
                                currentOutput = createOutputBuffer(MAX_INITIAL_OUTPUT_BUFFER);
                                currentWriter = outputFactory.createXMLStreamWriter(currentOutput, StandardCharsets.UTF_8.name());
                            }
                            case OP_START_DOCUMENT -> {
                                String version = batch.str0[i];
                                if (version == null) {
                                    currentWriter.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
                                } else {
                                    currentWriter.writeStartDocument(StandardCharsets.UTF_8.name(), version);
                                }
                            }
                            case OP_START_ELEMENT -> writeStartElement(currentWriter, batch.str0[i], batch.str1[i], batch.str2[i]);
                            case OP_NAMESPACE -> writeNamespace(currentWriter, batch.str0[i], batch.str1[i]);
                            case OP_ATTRIBUTE -> writeAttribute(currentWriter, batch.str0[i], batch.str1[i], batch.str2[i], batch.str3[i]);
                            case OP_CHARACTERS -> currentWriter.writeCharacters(batch.str0[i]);
                            case OP_CDATA -> currentWriter.writeCData(batch.str0[i]);
                            case OP_COMMENT -> currentWriter.writeComment(batch.str0[i]);
                            case OP_PROCESSING_INSTRUCTION -> {
                                if (batch.str1[i] == null) {
                                    currentWriter.writeProcessingInstruction(batch.str0[i]);
                                } else {
                                    currentWriter.writeProcessingInstruction(batch.str0[i], batch.str1[i]);
                                }
                            }
                            case OP_DTD -> currentWriter.writeDTD(batch.str0[i]);
                            case OP_END_ELEMENT -> currentWriter.writeEndElement();
                            case OP_END_DOCUMENT -> currentWriter.writeEndDocument();
                            case OP_FINISH_DOCUMENT -> {
                                if (currentWriter != null) {
                                    currentWriter.flush();
                                    currentWriter.close();
                                }
                                if (currentOutput != null) {
                                    byte[] encoded = currentOutput.toByteArray();
                                    currentTotalBytes += encoded.length;
                                    currentSampleChecksum = foldSample(currentSampleChecksum, encoded);
                                    currentDocumentCount++;
                                }
                                currentWriter = null;
                                currentOutput = null;
                            }
                            case OP_END_BATCH -> {
                                totalBytes = currentTotalBytes;
                                documentCount = currentDocumentCount;
                                sampleChecksum = currentSampleChecksum;
                                completedBatchId = batch.long0[i];
                            }
                            case OP_STOP -> running = false;
                            default -> throw new IllegalStateException("未知批量 opcode: " + opcode);
                        }
                    }
                } catch (Throwable throwable) {
                    failure = throwable;
                    completedBatchId = Long.MAX_VALUE;
                    running = false;
                } finally {
                    recycleBatch(batch);
                }
            }
        }

        @Override
        public void close() {
            if (workerThread.isAlive()) {
                CommandBatch batch = acquireBatch();
                batch.add(OP_STOP, null, null, null, null, 0L);
                submitBatch(batch);
            }
            try {
                workerThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 批量队列生产端事件接收器。
     */
    private static final class BatchQueueXmlEventSink implements XmlEventSink {
        private final BatchedQueuedCopyWorker worker;
        private CommandBatch                  currentBatch;

        private BatchQueueXmlEventSink(final BatchedQueuedCopyWorker worker) {
            this.worker = worker;
        }

        @Override
        public void startDocument(final String version) {
            append(BatchedQueuedCopyWorker.OP_START_DOCUMENT, version, null, null, null, 0L);
        }

        @Override
        public void startElement(final String prefix,
                                 final String localName,
                                 final String namespaceUri) {
            append(BatchedQueuedCopyWorker.OP_START_ELEMENT, prefix, localName, namespaceUri, null, 0L);
        }

        @Override
        public void namespace(final String prefix,
                              final String namespaceUri) {
            append(BatchedQueuedCopyWorker.OP_NAMESPACE, prefix, namespaceUri, null, null, 0L);
        }

        @Override
        public void attribute(final String prefix,
                              final String namespaceUri,
                              final String localName,
                              final String value) {
            append(BatchedQueuedCopyWorker.OP_ATTRIBUTE, prefix, namespaceUri, localName, value, 0L);
        }

        @Override
        public void characters(final String text) {
            append(BatchedQueuedCopyWorker.OP_CHARACTERS, text, null, null, null, 0L);
        }

        @Override
        public void cdata(final String text) {
            append(BatchedQueuedCopyWorker.OP_CDATA, text, null, null, null, 0L);
        }

        @Override
        public void comment(final String text) {
            append(BatchedQueuedCopyWorker.OP_COMMENT, text, null, null, null, 0L);
        }

        @Override
        public void processingInstruction(final String target,
                                          final String data) {
            append(BatchedQueuedCopyWorker.OP_PROCESSING_INSTRUCTION, target, data, null, null, 0L);
        }

        @Override
        public void dtd(final String text) {
            append(BatchedQueuedCopyWorker.OP_DTD, text, null, null, null, 0L);
        }

        @Override
        public void endElement() {
            append(BatchedQueuedCopyWorker.OP_END_ELEMENT, null, null, null, null, 0L);
        }

        @Override
        public void endDocument() {
            append(BatchedQueuedCopyWorker.OP_END_DOCUMENT, null, null, null, null, 0L);
        }

        private void control(final byte opcode,
                             final long value) {
            append(opcode, null, null, null, null, value);
        }

        private void finish() {
            flush();
        }

        private void append(final byte opcode,
                            final String str0,
                            final String str1,
                            final String str2,
                            final String str3,
                            final long long0) {
            if (currentBatch == null) {
                currentBatch = worker.acquireBatch();
            }
            if (currentBatch.isFull()) {
                flush();
                currentBatch = worker.acquireBatch();
            }
            currentBatch.add(opcode, str0, str1, str2, str3, long0);
        }

        private void flush() {
            if (currentBatch == null || currentBatch.isEmpty()) {
                return;
            }
            worker.submitBatch(currentBatch);
            currentBatch = null;
        }
    }

    /**
     * 可复用的批量命令缓冲区。
     */
    private static final class CommandBatch {
        private final byte[]   opcodes;
        private final String[] str0;
        private final String[] str1;
        private final String[] str2;
        private final String[] str3;
        private final long[]   long0;
        private int            size;

        private CommandBatch(final int capacity) {
            this.opcodes = new byte[capacity];
            this.str0 = new String[capacity];
            this.str1 = new String[capacity];
            this.str2 = new String[capacity];
            this.str3 = new String[capacity];
            this.long0 = new long[capacity];
        }

        private boolean isFull() {
            return size == opcodes.length;
        }

        private boolean isEmpty() {
            return size == 0;
        }

        private void add(final byte opcode,
                         final String value0,
                         final String value1,
                         final String value2,
                         final String value3,
                         final long valueLong0) {
            opcodes[size] = opcode;
            str0[size] = value0;
            str1[size] = value1;
            str2[size] = value2;
            str3[size] = value3;
            long0[size] = valueLong0;
            size++;
        }

        private void reset() {
            for (int i = 0; i < size; i++) {
                str0[i] = null;
                str1[i] = null;
                str2[i] = null;
                str3[i] = null;
                long0[i] = 0L;
                opcodes[i] = 0;
            }
            size = 0;
        }
    }

    private static int roundUpPowerOfTwo(final int value) {
        int rounded = 1;
        while (rounded < value) {
            rounded <<= 1;
        }
        return rounded;
    }

    private static void writeStartElement(final XMLStreamWriter writer,
                                          final String prefix,
                                          final String localName,
                                          final String namespaceUri) throws XMLStreamException {
        if (namespaceUri != null && prefix != null) {
            writer.writeStartElement(prefix, localName, namespaceUri);
        } else if (namespaceUri != null) {
            writer.writeStartElement("", localName, namespaceUri);
        } else {
            writer.writeStartElement(localName);
        }
    }

    private static void writeNamespace(final XMLStreamWriter writer,
                                       final String prefix,
                                       final String namespaceUri) throws XMLStreamException {
        if (prefix == null) {
            writer.writeDefaultNamespace(Objects.requireNonNullElse(namespaceUri, ""));
        } else {
            writer.writeNamespace(prefix, Objects.requireNonNullElse(namespaceUri, ""));
        }
    }

    private static void writeAttribute(final XMLStreamWriter writer,
                                       final String prefix,
                                       final String namespaceUri,
                                       final String localName,
                                       final String value) throws XMLStreamException {
        if (namespaceUri != null && prefix != null) {
            writer.writeAttribute(prefix, namespaceUri, localName, value);
        } else if (namespaceUri != null) {
            writer.writeAttribute(namespaceUri, localName, value);
        } else {
            writer.writeAttribute(localName, value);
        }
    }

    /**
     * 队列中的 XML 写指令。
     */
    private interface XmlCommand {
        void apply(XMLStreamWriter writer) throws XMLStreamException;
    }

    private enum ControlType {
        BEGIN_BATCH,
        BEGIN_DOCUMENT,
        END_DOCUMENT,
        END_BATCH,
        STOP
    }

    private record ControlCommand(ControlType type, long batchId) implements XmlCommand {
        @Override
        public void apply(final XMLStreamWriter writer) {
            throw new UnsupportedOperationException("控制指令不会直接应用到 XMLStreamWriter");
        }
    }

    private record StartDocumentCommand(String version) implements XmlCommand {
        @Override
        public void apply(final XMLStreamWriter writer) throws XMLStreamException {
            if (version == null) {
                writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            } else {
                writer.writeStartDocument(StandardCharsets.UTF_8.name(), version);
            }
        }
    }

    private record StartElementCommand(String prefix, String localName, String namespaceUri) implements XmlCommand {
        @Override
        public void apply(final XMLStreamWriter writer) throws XMLStreamException {
            if (namespaceUri != null && prefix != null) {
                writer.writeStartElement(prefix, localName, namespaceUri);
            } else if (namespaceUri != null) {
                writer.writeStartElement("", localName, namespaceUri);
            } else {
                writer.writeStartElement(localName);
            }
        }
    }

    private record NamespaceCommand(String prefix, String namespaceUri) implements XmlCommand {
        @Override
        public void apply(final XMLStreamWriter writer) throws XMLStreamException {
            if (prefix == null) {
                writer.writeDefaultNamespace(Objects.requireNonNullElse(namespaceUri, ""));
            } else {
                writer.writeNamespace(prefix, Objects.requireNonNullElse(namespaceUri, ""));
            }
        }
    }

    private record AttributeCommand(String prefix,
                                    String namespaceUri,
                                    String localName,
                                    String value) implements XmlCommand {
        @Override
        public void apply(final XMLStreamWriter writer) throws XMLStreamException {
            if (namespaceUri != null && prefix != null) {
                writer.writeAttribute(prefix, namespaceUri, localName, value);
            } else if (namespaceUri != null) {
                writer.writeAttribute(namespaceUri, localName, value);
            } else {
                writer.writeAttribute(localName, value);
            }
        }
    }

    private record CharactersCommand(String text) implements XmlCommand {
        @Override
        public void apply(final XMLStreamWriter writer) throws XMLStreamException {
            writer.writeCharacters(text);
        }
    }

    private record CDataCommand(String text) implements XmlCommand {
        @Override
        public void apply(final XMLStreamWriter writer) throws XMLStreamException {
            writer.writeCData(text);
        }
    }

    private record CommentCommand(String text) implements XmlCommand {
        @Override
        public void apply(final XMLStreamWriter writer) throws XMLStreamException {
            writer.writeComment(text);
        }
    }

    private record ProcessingInstructionCommand(String target, String data) implements XmlCommand {
        @Override
        public void apply(final XMLStreamWriter writer) throws XMLStreamException {
            if (data == null) {
                writer.writeProcessingInstruction(target);
            } else {
                writer.writeProcessingInstruction(target, data);
            }
        }
    }

    private record DtdCommand(String text) implements XmlCommand {
        @Override
        public void apply(final XMLStreamWriter writer) throws XMLStreamException {
            writer.writeDTD(text);
        }
    }

    private enum EndElementCommand implements XmlCommand {
        INSTANCE;

        @Override
        public void apply(final XMLStreamWriter writer) throws XMLStreamException {
            writer.writeEndElement();
        }
    }

    private enum EndDocumentCommand implements XmlCommand {
        INSTANCE;

        @Override
        public void apply(final XMLStreamWriter writer) throws XMLStreamException {
            writer.writeEndDocument();
        }
    }
}