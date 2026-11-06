package github.kasuminova.ssoptimizer.api;

import java.util.ArrayList;
import java.util.List;

/**
 * 将同一目标类的多个 {@link AsmClassProcessor} 组成有序处理链，适配只接受单处理器的注册入口。
 * <p>
 * 实例不可变，处理过程在调用线程同步完成；实例能否并发使用取决于链中各处理器。
 */
public final class CompositeAsmClassProcessor implements AsmClassProcessor {
    private final List<AsmClassProcessor> processors;

    /**
     * 按给定顺序构造处理链，并防御性复制处理器列表。
     *
     * @param processors 按执行顺序排列的处理器，列表及其元素均不得为 {@code null}
     * @throws NullPointerException 列表或任一元素为 {@code null}
     */
    public CompositeAsmClassProcessor(final List<AsmClassProcessor> processors) {
        this.processors = List.copyOf(processors);
    }

    /**
     * 创建处理链，忽略 {@code null} 元素；仅有一个有效处理器时直接返回该实例。
     *
     * @param processors 处理器数组，数组本身不得为 {@code null}，元素可为 {@code null}
     * @return 多个有效处理器组成的复合处理器，或唯一有效的处理器
     * @throws IllegalArgumentException 没有任何有效处理器
     * @throws NullPointerException     处理器数组为 {@code null}
     */
    public static AsmClassProcessor of(final AsmClassProcessor... processors) {
        final List<AsmClassProcessor> chain = new ArrayList<>(processors.length);
        for (AsmClassProcessor processor : processors) {
            if (processor != null) {
                chain.add(processor);
            }
        }
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("CompositeAsmClassProcessor requires at least one processor");
        }
        if (chain.size() == 1) {
            return chain.getFirst();
        }
        return new CompositeAsmClassProcessor(chain);
    }

    /**
     * 按构造顺序执行处理链，每个非 {@code null} 返回值作为后续处理器的输入。
     * 任一处理器抛出异常时中止后续处理并向调用方传播。
     *
     * @param classfileBuffer 原始类文件字节码
     * @return 至少一个处理器修改时返回累积处理后的完整字节码；全部未修改时返回 {@code null}
     */
    @Override
    public byte[] process(final byte[] classfileBuffer) {
        byte[] current = classfileBuffer;
        boolean modified = false;
        for (AsmClassProcessor processor : processors) {
            final byte[] processed = processor.process(current);
            if (processed != null) {
                current = processed;
                modified = true;
            }
        }
        return modified ? current : null;
    }
}
