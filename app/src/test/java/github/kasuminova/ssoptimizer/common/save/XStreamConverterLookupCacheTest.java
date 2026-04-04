package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class XStreamConverterLookupCacheTest {
    @Test
    void remembersAndReturnsConvertersByType() {
        XStreamConverterLookupCache cache = new XStreamConverterLookupCache();
        Converter converter = new SampleConverter(String.class);

        cache.remember(String.class, converter);

        assertSame(converter, cache.lookup(String.class));
        assertNull(cache.lookup(Integer.class));
    }

    @Test
    void clearDropsRememberedConverters() {
        XStreamConverterLookupCache cache = new XStreamConverterLookupCache();
        Converter converter = new SampleConverter(String.class);

        cache.remember(String.class, converter);
        cache.clear();

        assertNull(cache.lookup(String.class));
    }

    @Test
    void ignoresNullLookupTypes() {
        XStreamConverterLookupCache cache = new XStreamConverterLookupCache();
        Converter converter = new SampleConverter(String.class);

        cache.remember(null, converter);

        assertNull(cache.lookup(null));
        assertNull(cache.lookup(String.class));
    }

    private record SampleConverter(Class<?> supportedType) implements Converter {
        @Override
        public boolean canConvert(final Class type) {
            return supportedType == type;
        }

        @Override
        public void marshal(final Object source,
                            final HierarchicalStreamWriter writer,
                            final MarshallingContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object unmarshal(final HierarchicalStreamReader reader,
                                final UnmarshallingContext context) {
            throw new UnsupportedOperationException();
        }
    }
}