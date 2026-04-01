package github.kasuminova.ssoptimizer.bootstrap;

import java.util.ArrayList;
import java.util.List;

/**
 * 组合多个 {@link AsmClassProcessor} 的复合处理器，按顺序串联执行。
 * <p>
 * Applies multiple bytecode processors to the same class in sequence.
 */
public final class CompositeAsmClassProcessor implements AsmClassProcessor {
    private final List<AsmClassProcessor> processors;

    /**
     * 构造复合处理器。
     *
     * @param processors 处理器列表，将被防御性复制
     */
    public CompositeAsmClassProcessor(final List<AsmClassProcessor> processors) {
        this.processors = List.copyOf(processors);
    }

    /**
     * 从多个处理器创建复合处理器。
     * <p>
     * 自动过滤 {@code null} 处理器；若只有一个有效处理器，直接返回该处理器而非包装。
     *
     * @param processors 处理器数组，允许包含 {@code null} 元素
     * @return 复合处理器或唯一有效的处理器
     * @throws IllegalArgumentException 若无任何有效处理器
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
     * 按注册顺序依次执行所有处理器。
     * <p>
     * 每个处理器的输出作为下一个处理器的输入；若任一处理器返回非 {@code null}，
     * 最终返回累积处理后的字节码，否则返回 {@code null}。
     *
     * @param classfileBuffer 原始类文件字节码
     * @return 处理后的字节码；若所有处理器均未修改则返回 {@code null}
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