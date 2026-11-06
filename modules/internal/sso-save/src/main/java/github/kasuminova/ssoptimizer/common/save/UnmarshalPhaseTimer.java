package github.kasuminova.ssoptimizer.common.save;

import org.apache.log4j.Logger;

import java.util.Locale;

public final class UnmarshalPhaseTimer {
    private static final Logger LOGGER = Logger.getLogger(UnmarshalPhaseTimer.class);

    private static volatile long beginNanos = -1;
    private static volatile long lastUnmarshalMs = -1;

    private UnmarshalPhaseTimer() {
    }

    public static void begin() {
        if (beginNanos < 0) {
            beginNanos = System.nanoTime();
        }
    }

    public static void end() {
        final long begin = beginNanos;
        if (begin < 0) {
            return;
        }
        beginNanos = -1;
        lastUnmarshalMs = (System.nanoTime() - begin) / 1_000_000;
        LOGGER.info(String.format(Locale.ROOT, "[SSO-Save] campaign.xml unmarshal: %d ms", lastUnmarshalMs));
    }

    public static long lastUnmarshalMs() {
        return lastUnmarshalMs;
    }
}
