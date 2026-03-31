package github.kasuminova.ssoptimizer.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies multiple bytecode processors to the same class in sequence.
 */
public final class CompositeAsmClassProcessor implements AsmClassProcessor {
    private final List<AsmClassProcessor> processors;

    public CompositeAsmClassProcessor(final List<AsmClassProcessor> processors) {
        this.processors = List.copyOf(processors);
    }

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