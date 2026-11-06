package github.kasuminova.ssoptimizer.common.save;

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

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class XStreamReferenceIdStringBenchmark {
    @State(Scope.Thread)
    public static class SequenceState {
        @Param({"1024", "65535", "262144", "1048576"})
        public int maxValue;

        private int currentValue;

        @Setup(Level.Trial)
        public void setup() {
            XStreamReferenceIdHelper.resetAdaptiveCacheForTests();
            XStreamReferenceIdHelper.requestWarmupToForTests(maxValue);
            if (!XStreamReferenceIdHelper.awaitWarmup(30_000L)) {
                throw new IllegalStateException("等待 XStream 引用 ID 池预热超时: maxValue=" + maxValue);
            }
            currentValue = 0;
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            XStreamReferenceIdHelper.resetAdaptiveCacheForTests();
        }

        int nextValue() {
            currentValue++;
            if (currentValue > maxValue) {
                currentValue = 1;
            }
            return currentValue;
        }
    }

    @Benchmark
    public String decimalStringValue(final SequenceState state) {
        return Integer.toString(state.nextValue());
    }

    @Benchmark
    public String javaBase36(final SequenceState state) {
        return Integer.toString(state.nextValue(), 36);
    }

    @Benchmark
    public String helperBase36Uncached(final SequenceState state) {
        return XStreamReferenceIdHelper.toCompactStringUncached(state.nextValue());
    }

    @Benchmark
    public String helperBase36Cached(final SequenceState state) {
        return XStreamReferenceIdHelper.nextReferenceId(state.nextValue());
    }
}