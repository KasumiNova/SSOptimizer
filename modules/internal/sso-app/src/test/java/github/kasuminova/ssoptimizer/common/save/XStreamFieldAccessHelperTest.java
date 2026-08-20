package github.kasuminova.ssoptimizer.common.save;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class XStreamFieldAccessHelperTest {
    @Test
    void readsAndWritesPrivateInstanceFields() throws Exception {
        Sample sample = new Sample();
        Field numberField = Sample.class.getDeclaredField("number");
        Field textField = Sample.class.getDeclaredField("text");
        numberField.setAccessible(true);
        textField.setAccessible(true);

        assertEquals(Integer.valueOf(7), XStreamFieldAccessHelper.read(numberField, sample));
        assertEquals("alpha", XStreamFieldAccessHelper.read(textField, sample));

        XStreamFieldAccessHelper.write(numberField, sample, 42);
        XStreamFieldAccessHelper.write(textField, sample, "beta");

        assertEquals(42, sample.number);
        assertEquals("beta", sample.text);
    }

    @Test
    void readsAndWritesPrivateStaticFields() throws Exception {
        Field counterField = Sample.class.getDeclaredField("counter");
        counterField.setAccessible(true);
        long original = Sample.counter;
        try {
            assertEquals(Long.valueOf(original), XStreamFieldAccessHelper.read(counterField, null));

            XStreamFieldAccessHelper.write(counterField, null, 99L);

            assertEquals(99L, Sample.counter);
            assertEquals(Long.valueOf(99L), XStreamFieldAccessHelper.read(counterField, null));
        } finally {
            Sample.counter = original;
        }
    }

    @Test
    void reusesCachedAccessorInstances() throws Exception {
        Field numberField = Sample.class.getDeclaredField("number");
        numberField.setAccessible(true);
        Method accessorFor = XStreamFieldAccessHelper.class.getDeclaredMethod("accessorFor", Field.class);
        accessorFor.setAccessible(true);

        Object first = accessorFor.invoke(null, numberField);
        Object second = accessorFor.invoke(null, numberField);

        assertSame(first, second);
    }

    private static final class Sample {
        private static long counter = 5L;

        private int    number = 7;
        private String text   = "alpha";
    }
}