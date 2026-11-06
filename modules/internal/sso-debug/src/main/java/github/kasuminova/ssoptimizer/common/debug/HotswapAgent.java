package github.kasuminova.ssoptimizer.common.debug;

import java.lang.instrument.Instrumentation;

public final class HotswapAgent {
    public static volatile Instrumentation INST;

    private HotswapAgent() {
    }

    public static void agentmain(final String args, final Instrumentation inst) {
        INST = inst;
    }
}
