package github.kasuminova.ssoptimizer.common.save;

import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XStreamFieldAccessHelperTest {
    @Test
    void readsAndWritesPrivateInstanceFields() throws Throwable {
        final Sample sample = new Sample();
        final MethodHandles.Lookup lookup = MethodHandles.lookup();
        final XStreamFieldAccessHelper.FieldAccessor numbers = XStreamFieldAccessHelper.createAccessor(
                lookup.findVarHandle(Sample.class, "number", int.class), false);
        final XStreamFieldAccessHelper.FieldAccessor texts = XStreamFieldAccessHelper.createAccessor(
                lookup.findVarHandle(Sample.class, "text", String.class), false);

        assertEquals(sample.number, numbers.read(sample));
        assertEquals(sample.text, texts.read(sample));

        numbers.write(sample, 42);
        texts.write(sample, "beta");

        assertEquals(42, sample.number);
        assertEquals("beta", sample.text);
    }

    @Test
    void readsAndWritesPrivateStaticFields() throws Throwable {
        final VarHandle counterHandle = MethodHandles.lookup().findStaticVarHandle(
                Sample.class, "counter", long.class);
        final XStreamFieldAccessHelper.FieldAccessor counter =
                XStreamFieldAccessHelper.createAccessor(counterHandle, true);
        final long original = Sample.counter;
        try {
            assertEquals(original, counter.read(null));

            counter.write(null, original + 1L);

            assertEquals(original + 1L, Sample.counter);
            assertEquals(Sample.counter, counter.read(null));
        } finally {
            Sample.counter = original;
        }
    }

    private static final class Sample {
        private static long counter = 5L;

        private int    number = 7;
        private String text   = "alpha";
    }
}
