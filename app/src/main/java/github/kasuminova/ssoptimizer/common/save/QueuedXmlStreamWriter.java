package github.kasuminova.ssoptimizer.common.save;

import org.jctools.queues.SpscArrayQueue;

import javax.xml.namespace.NamespaceContext;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.util.concurrent.locks.LockSupport;

/**
 * 基于批量 SPSC 队列的 XML 写入器包装器。
 * <p>
 * 职责：把调用线程提交的 XML 写指令批量转移到单独的后台写线程执行，以减少调用线程在大存档写出阶段
 * 直接承担的格式化与输出开销。<br>
 * 设计动机：JMH 基准表明，在真实 {@code campaign.xml} 量级的语料上，将写指令批量化后交给单独线程执行，
 * 相比调用线程直接写出与“每事件一个对象”的队列模型都有更稳定的收益。<br>
 * 兼容性策略：通过 {@link #flush()} 和 {@link #close()} 建立显式屏障，确保调用方在需要观察到底层输出状态时
 * 能等待前序所有写指令完成；若后台线程失败，则在前台调用点抛出异常并停止后续写入。
 */
final class QueuedXmlStreamWriter implements XMLStreamWriter {
    private static final byte OP_WRITE_START_ELEMENT_LOCAL = 1;
    private static final byte OP_WRITE_START_ELEMENT_NAMESPACE = 2;
    private static final byte OP_WRITE_START_ELEMENT_PREFIX = 3;
    private static final byte OP_WRITE_EMPTY_ELEMENT_NAMESPACE = 4;
    private static final byte OP_WRITE_EMPTY_ELEMENT_PREFIX = 5;
    private static final byte OP_WRITE_EMPTY_ELEMENT_LOCAL = 6;
    private static final byte OP_WRITE_END_ELEMENT = 7;
    private static final byte OP_WRITE_END_DOCUMENT = 8;
    private static final byte OP_WRITE_ATTRIBUTE_LOCAL = 9;
    private static final byte OP_WRITE_ATTRIBUTE_PREFIX = 10;
    private static final byte OP_WRITE_ATTRIBUTE_NAMESPACE = 11;
    private static final byte OP_WRITE_NAMESPACE = 12;
    private static final byte OP_WRITE_DEFAULT_NAMESPACE = 13;
    private static final byte OP_WRITE_COMMENT = 14;
    private static final byte OP_WRITE_PROCESSING_INSTRUCTION_TARGET = 15;
    private static final byte OP_WRITE_PROCESSING_INSTRUCTION_FULL = 16;
    private static final byte OP_WRITE_CDATA = 17;
    private static final byte OP_WRITE_DTD = 18;
    private static final byte OP_WRITE_ENTITY_REF = 19;
    private static final byte OP_WRITE_START_DOCUMENT = 20;
    private static final byte OP_WRITE_START_DOCUMENT_VERSION = 21;
    private static final byte OP_WRITE_START_DOCUMENT_ENCODING = 22;
    private static final byte OP_WRITE_CHARACTERS = 23;
    private static final byte OP_SET_PREFIX = 24;
    private static final byte OP_SET_DEFAULT_NAMESPACE = 25;
    private static final byte OP_SET_NAMESPACE_CONTEXT = 26;
    private static final byte OP_FLUSH = 27;
    private static final byte OP_CLOSE = 28;

    private final XMLStreamWriter               delegate;
    private final SpscArrayQueue<CommandBatch> freeQueue;
    private final SpscArrayQueue<CommandBatch> readyQueue;
    private final Thread                        workerThread;

    private volatile boolean   running = true;
    private volatile boolean   closed;
    private volatile boolean   documentEnded;
    private volatile Throwable failure;
    private volatile long      completedBarrierSequence;

    private long        nextBarrierSequence = 1L;
    private CommandBatch producerBatch;
    private int         producerOperationCount;

    /**
     * 创建批量队列写入器。
     *
     * @param delegate      实际执行写出的底层 XML 写入器
     * @param queueCapacity 目标事件容量，用于推导批次数量
     * @param batchSize     单个批次的事件容量
     */
    QueuedXmlStreamWriter(final XMLStreamWriter delegate,
                          final int queueCapacity,
                          final int batchSize) {
        this.delegate = delegate;

        final int effectiveBatchSize = Math.max(16, batchSize);
        final int batchQueueCapacity = roundUpPowerOfTwo(Math.max(4, queueCapacity / effectiveBatchSize));

        this.freeQueue = new SpscArrayQueue<>(batchQueueCapacity);
        this.readyQueue = new SpscArrayQueue<>(batchQueueCapacity);
        for (int i = 0; i < batchQueueCapacity; i++) {
            freeQueue.offer(new CommandBatch(effectiveBatchSize));
        }

        this.workerThread = Thread.ofPlatform()
                                  .name("ssoptimizer-txw2-queued-writer")
                                  .daemon(true)
                                  .start(this::runLoop);
    }

    /**
     * 写入本地开始标签。
     */
    @Override
    public void writeStartElement(final String localName) throws XMLStreamException {
        enqueue(OP_WRITE_START_ELEMENT_LOCAL, localName, null, null, null, 0L);
    }

    /**
     * 写入带命名空间的开始标签。
     */
    @Override
    public void writeStartElement(final String namespaceURI, final String localName) throws XMLStreamException {
        enqueue(OP_WRITE_START_ELEMENT_NAMESPACE, namespaceURI, localName, null, null, 0L);
    }

    /**
     * 写入带前缀和命名空间的开始标签。
     */
    @Override
    public void writeStartElement(final String prefix,
                                  final String localName,
                                  final String namespaceURI) throws XMLStreamException {
        enqueue(OP_WRITE_START_ELEMENT_PREFIX, prefix, localName, namespaceURI, null, 0L);
    }

    /**
     * 写入带命名空间的空标签。
     */
    @Override
    public void writeEmptyElement(final String namespaceURI, final String localName) throws XMLStreamException {
        enqueue(OP_WRITE_EMPTY_ELEMENT_NAMESPACE, namespaceURI, localName, null, null, 0L);
    }

    /**
     * 写入带前缀和命名空间的空标签。
     */
    @Override
    public void writeEmptyElement(final String prefix,
                                  final String localName,
                                  final String namespaceURI) throws XMLStreamException {
        enqueue(OP_WRITE_EMPTY_ELEMENT_PREFIX, prefix, localName, namespaceURI, null, 0L);
    }

    /**
     * 写入本地空标签。
     */
    @Override
    public void writeEmptyElement(final String localName) throws XMLStreamException {
        enqueue(OP_WRITE_EMPTY_ELEMENT_LOCAL, localName, null, null, null, 0L);
    }

    /**
     * 写入结束标签。
     */
    @Override
    public void writeEndElement() throws XMLStreamException {
        enqueue(OP_WRITE_END_ELEMENT, null, null, null, null, 0L);
    }

    /**
     * 写入文档结束。
     */
    @Override
    public void writeEndDocument() throws XMLStreamException {
        documentEnded = true;
        enqueue(OP_WRITE_END_DOCUMENT, null, null, null, null, 0L);
    }

    /**
     * 关闭写入器并等待后台线程完成所有写出。
     */
    @Override
    public void close() throws XMLStreamException {
        if (closed) {
            return;
        }

        finishAndJoin(OP_CLOSE);
    }

    /**
     * 刷新写入器并等待前序写指令全部完成。
     */
    @Override
    public void flush() throws XMLStreamException {
        if (closed) {
            checkFailure();
            return;
        }

        finishAndJoin(documentEnded ? OP_CLOSE : OP_FLUSH);
    }

    /**
     * 写入本地属性。
     */
    @Override
    public void writeAttribute(final String localName, final String value) throws XMLStreamException {
        enqueue(OP_WRITE_ATTRIBUTE_LOCAL, localName, value, null, null, 0L);
    }

    /**
     * 写入带前缀和命名空间的属性。
     */
    @Override
    public void writeAttribute(final String prefix,
                               final String namespaceURI,
                               final String localName,
                               final String value) throws XMLStreamException {
        enqueue(OP_WRITE_ATTRIBUTE_PREFIX, prefix, namespaceURI, localName, value, 0L);
    }

    /**
     * 写入带命名空间的属性。
     */
    @Override
    public void writeAttribute(final String namespaceURI,
                               final String localName,
                               final String value) throws XMLStreamException {
        enqueue(OP_WRITE_ATTRIBUTE_NAMESPACE, namespaceURI, localName, value, null, 0L);
    }

    /**
     * 写入命名空间声明。
     */
    @Override
    public void writeNamespace(final String prefix, final String namespaceURI) throws XMLStreamException {
        enqueue(OP_WRITE_NAMESPACE, prefix, namespaceURI, null, null, 0L);
    }

    /**
     * 写入默认命名空间声明。
     */
    @Override
    public void writeDefaultNamespace(final String namespaceURI) throws XMLStreamException {
        enqueue(OP_WRITE_DEFAULT_NAMESPACE, namespaceURI, null, null, null, 0L);
    }

    /**
     * 写入注释。
     */
    @Override
    public void writeComment(final String data) throws XMLStreamException {
        enqueue(OP_WRITE_COMMENT, data, null, null, null, 0L);
    }

    /**
     * 写入只含目标的处理指令。
     */
    @Override
    public void writeProcessingInstruction(final String target) throws XMLStreamException {
        enqueue(OP_WRITE_PROCESSING_INSTRUCTION_TARGET, target, null, null, null, 0L);
    }

    /**
     * 写入完整处理指令。
     */
    @Override
    public void writeProcessingInstruction(final String target, final String data) throws XMLStreamException {
        enqueue(OP_WRITE_PROCESSING_INSTRUCTION_FULL, target, data, null, null, 0L);
    }

    /**
     * 写入 CDATA。
     */
    @Override
    public void writeCData(final String data) throws XMLStreamException {
        enqueue(OP_WRITE_CDATA, data, null, null, null, 0L);
    }

    /**
     * 写入 DTD。
     */
    @Override
    public void writeDTD(final String dtd) throws XMLStreamException {
        enqueue(OP_WRITE_DTD, dtd, null, null, null, 0L);
    }

    /**
     * 写入实体引用。
     */
    @Override
    public void writeEntityRef(final String name) throws XMLStreamException {
        enqueue(OP_WRITE_ENTITY_REF, name, null, null, null, 0L);
    }

    /**
     * 写入 XML 文档头。
     */
    @Override
    public void writeStartDocument() throws XMLStreamException {
        enqueue(OP_WRITE_START_DOCUMENT, null, null, null, null, 0L);
    }

    /**
     * 写入带版本号的 XML 文档头。
     */
    @Override
    public void writeStartDocument(final String version) throws XMLStreamException {
        enqueue(OP_WRITE_START_DOCUMENT_VERSION, version, null, null, null, 0L);
    }

    /**
     * 写入带编码与版本号的 XML 文档头。
     */
    @Override
    public void writeStartDocument(final String encoding, final String version) throws XMLStreamException {
        enqueue(OP_WRITE_START_DOCUMENT_ENCODING, encoding, version, null, null, 0L);
    }

    /**
     * 写入文本内容。
     */
    @Override
    public void writeCharacters(final String text) throws XMLStreamException {
        enqueue(OP_WRITE_CHARACTERS, text, null, null, null, 0L);
    }

    /**
     * 写入字符数组内容。
     */
    @Override
    public void writeCharacters(final char[] text, final int start, final int len) throws XMLStreamException {
        enqueue(OP_WRITE_CHARACTERS, new String(text, start, len), null, null, null, 0L);
    }

    /**
     * 获取指定命名空间前缀。
     */
    @Override
    public String getPrefix(final String uri) throws XMLStreamException {
        flush();
        return delegate.getPrefix(uri);
    }

    /**
     * 设置命名空间前缀。
     */
    @Override
    public void setPrefix(final String prefix, final String uri) throws XMLStreamException {
        enqueue(OP_SET_PREFIX, prefix, uri, null, null, 0L);
    }

    /**
     * 设置默认命名空间。
     */
    @Override
    public void setDefaultNamespace(final String uri) throws XMLStreamException {
        enqueue(OP_SET_DEFAULT_NAMESPACE, uri, null, null, null, 0L);
    }

    /**
     * 设置命名空间上下文。
     */
    @Override
    public void setNamespaceContext(final NamespaceContext context) throws XMLStreamException {
        enqueue(OP_SET_NAMESPACE_CONTEXT, null, null, null, context, 0L);
    }

    /**
     * 获取当前命名空间上下文。
     */
    @Override
    public NamespaceContext getNamespaceContext() {
        flushUnchecked();
        return delegate.getNamespaceContext();
    }

    /**
     * 获取底层写入器属性。
     */
    @Override
    public Object getProperty(final String name) throws IllegalArgumentException {
        flushUnchecked();
        return delegate.getProperty(name);
    }

    private void enqueue(final byte opcode,
                         final String str0,
                         final String str1,
                         final String str2,
                         final Object ref0,
                         final long long0) throws XMLStreamException {
        if (closed) {
            throw new XMLStreamException("队列 XML 写入器已关闭");
        }

        checkFailure();
        if ((++producerOperationCount & 0xFF) == 0) {
            SaveProgressOverlayCoordinator.hintMainThreadPump();
        }

        CommandBatch batch = producerBatch;
        if (batch == null) {
            batch = acquireBatch();
            producerBatch = batch;
        }
        if (batch.isFull()) {
            submitProducerBatch();
            batch = acquireBatch();
            producerBatch = batch;
        }

        batch.add(opcode, str0, str1, str2, ref0, long0);
    }

    private long submitBarrier(final byte opcode) throws XMLStreamException {
        submitProducerBatch();

        final long sequence = nextBarrierSequence++;
        final CommandBatch barrierBatch = acquireBatch();
        barrierBatch.add(opcode, null, null, null, null, sequence);
        submitBatch(barrierBatch);
        return sequence;
    }

    private void awaitBarrier(final long sequence) throws XMLStreamException {
        int waitCount = 0;
        while (completedBarrierSequence < sequence && failure == null) {
            if ((++waitCount & 0x3FF) == 0) {
                SaveProgressOverlayCoordinator.maybePumpFrame();
            }
            pauseWhileIdle(waitCount);
        }
        checkFailure();
    }

    private void finishAndJoin(final byte opcode) throws XMLStreamException {
        final long sequence = submitBarrier(opcode);
        awaitBarrier(sequence);
        if (opcode == OP_CLOSE) {
            closed = true;
            joinWorkerThread();
        }
    }

    private void joinWorkerThread() throws XMLStreamException {
        try {
            workerThread.join();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new XMLStreamException("等待队列写线程关闭时被中断", e);
        }
    }

    private void flushUnchecked() {
        try {
            flush();
        } catch (final XMLStreamException e) {
            throw new IllegalStateException("等待队列 XML 写出完成时失败", e);
        }
    }

    private void checkFailure() throws XMLStreamException {
        if (failure == null) {
            return;
        }
        if (failure instanceof XMLStreamException xmlStreamException) {
            throw xmlStreamException;
        }
        throw new XMLStreamException("后台 XML 写线程执行失败", failure);
    }

    private void submitProducerBatch() throws XMLStreamException {
        final CommandBatch batch = producerBatch;
        if (batch == null || batch.isEmpty()) {
            producerBatch = null;
            return;
        }

        submitBatch(batch);
        producerBatch = null;
    }

    private CommandBatch acquireBatch() throws XMLStreamException {
        int waitCount = 0;
        while (true) {
            final CommandBatch batch = freeQueue.relaxedPoll();
            if (batch != null) {
                batch.reset();
                return batch;
            }

            if (failure != null) {
                checkFailure();
            }
            if (!workerThread.isAlive()) {
                throw new XMLStreamException("队列 XML 写线程已终止");
            }
            pauseWhileIdle(++waitCount);
        }
    }

    private void submitBatch(final CommandBatch batch) throws XMLStreamException {
        int waitCount = 0;
        while (!readyQueue.offer(batch)) {
            if (failure != null) {
                checkFailure();
            }
            if (!workerThread.isAlive()) {
                throw new XMLStreamException("队列 XML 写线程已终止");
            }
            pauseWhileIdle(++waitCount);
        }
    }

    private void recycleBatch(final CommandBatch batch) {
        batch.reset();
        int waitCount = 0;
        while (!freeQueue.offer(batch)) {
            pauseWhileIdle(++waitCount);
        }
    }

    private void runLoop() {
        int idleCount = 0;
        while (running) {
            final CommandBatch batch = readyQueue.relaxedPoll();
            if (batch == null) {
                pauseWhileIdle(++idleCount);
                continue;
            }

            idleCount = 0;

            try {
                for (int i = 0; i < batch.size; i++) {
                    execute(batch, i);
                }
            } catch (final Throwable throwable) {
                failure = throwable;
                completedBarrierSequence = Long.MAX_VALUE;
                running = false;
            } finally {
                recycleBatch(batch);
            }
        }
    }

    private static void pauseWhileIdle(final int waitCount) {
        if (waitCount < 64) {
            Thread.onSpinWait();
            return;
        }

        final int backoffShift = Math.min(10, waitCount - 64);
        final long parkNanos = Math.min(1_000_000L, 1_000L << backoffShift);
        LockSupport.parkNanos(parkNanos);
    }

    private void execute(final CommandBatch batch, final int index) throws XMLStreamException {
        switch (batch.opcodes[index]) {
            case OP_WRITE_START_ELEMENT_LOCAL -> delegate.writeStartElement(batch.str0[index]);
            case OP_WRITE_START_ELEMENT_NAMESPACE -> delegate.writeStartElement(batch.str0[index], batch.str1[index]);
            case OP_WRITE_START_ELEMENT_PREFIX -> delegate.writeStartElement(batch.str0[index], batch.str1[index], batch.str2[index]);
            case OP_WRITE_EMPTY_ELEMENT_NAMESPACE -> delegate.writeEmptyElement(batch.str0[index], batch.str1[index]);
            case OP_WRITE_EMPTY_ELEMENT_PREFIX -> delegate.writeEmptyElement(batch.str0[index], batch.str1[index], batch.str2[index]);
            case OP_WRITE_EMPTY_ELEMENT_LOCAL -> delegate.writeEmptyElement(batch.str0[index]);
            case OP_WRITE_END_ELEMENT -> delegate.writeEndElement();
            case OP_WRITE_END_DOCUMENT -> delegate.writeEndDocument();
            case OP_WRITE_ATTRIBUTE_LOCAL -> delegate.writeAttribute(batch.str0[index], batch.str1[index]);
            case OP_WRITE_ATTRIBUTE_PREFIX -> delegate.writeAttribute(batch.str0[index], batch.str1[index], batch.str2[index], (String) batch.ref0[index]);
            case OP_WRITE_ATTRIBUTE_NAMESPACE -> delegate.writeAttribute(batch.str0[index], batch.str1[index], batch.str2[index]);
            case OP_WRITE_NAMESPACE -> delegate.writeNamespace(batch.str0[index], batch.str1[index]);
            case OP_WRITE_DEFAULT_NAMESPACE -> delegate.writeDefaultNamespace(batch.str0[index]);
            case OP_WRITE_COMMENT -> delegate.writeComment(batch.str0[index]);
            case OP_WRITE_PROCESSING_INSTRUCTION_TARGET -> delegate.writeProcessingInstruction(batch.str0[index]);
            case OP_WRITE_PROCESSING_INSTRUCTION_FULL -> delegate.writeProcessingInstruction(batch.str0[index], batch.str1[index]);
            case OP_WRITE_CDATA -> delegate.writeCData(batch.str0[index]);
            case OP_WRITE_DTD -> delegate.writeDTD(batch.str0[index]);
            case OP_WRITE_ENTITY_REF -> delegate.writeEntityRef(batch.str0[index]);
            case OP_WRITE_START_DOCUMENT -> delegate.writeStartDocument();
            case OP_WRITE_START_DOCUMENT_VERSION -> delegate.writeStartDocument(batch.str0[index]);
            case OP_WRITE_START_DOCUMENT_ENCODING -> delegate.writeStartDocument(batch.str0[index], batch.str1[index]);
            case OP_WRITE_CHARACTERS -> delegate.writeCharacters(batch.str0[index]);
            case OP_SET_PREFIX -> delegate.setPrefix(batch.str0[index], batch.str1[index]);
            case OP_SET_DEFAULT_NAMESPACE -> delegate.setDefaultNamespace(batch.str0[index]);
            case OP_SET_NAMESPACE_CONTEXT -> delegate.setNamespaceContext((NamespaceContext) batch.ref0[index]);
            case OP_FLUSH -> {
                delegate.flush();
                completedBarrierSequence = batch.long0[index];
            }
            case OP_CLOSE -> {
                delegate.flush();
                delegate.close();
                completedBarrierSequence = batch.long0[index];
                running = false;
            }
            default -> throw new XMLStreamException("未知队列 XML opcode: " + batch.opcodes[index]);
        }
    }

    private static int roundUpPowerOfTwo(final int value) {
        int rounded = 1;
        while (rounded < value) {
            rounded <<= 1;
        }
        return rounded;
    }

    /**
     * 可复用批量命令缓冲区。
     */
    private static final class CommandBatch {
        private final byte[]   opcodes;
        private final String[] str0;
        private final String[] str1;
        private final String[] str2;
        private final Object[] ref0;
        private final long[]   long0;
        private int            size;

        private CommandBatch(final int capacity) {
            this.opcodes = new byte[capacity];
            this.str0 = new String[capacity];
            this.str1 = new String[capacity];
            this.str2 = new String[capacity];
            this.ref0 = new Object[capacity];
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
                         final Object valueRef0,
                         final long valueLong0) {
            opcodes[size] = opcode;
            str0[size] = value0;
            str1[size] = value1;
            str2[size] = value2;
            ref0[size] = valueRef0;
            long0[size] = valueLong0;
            size++;
        }

        private void reset() {
            for (int i = 0; i < size; i++) {
                opcodes[i] = 0;
                str0[i] = null;
                str1[i] = null;
                str2[i] = null;
                ref0[i] = null;
                long0[i] = 0L;
            }
            size = 0;
        }
    }
}