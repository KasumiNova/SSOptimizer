package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.converters.ConversionException;
import com.thoughtworks.xstream.converters.reflection.ReflectionProvider;
import com.thoughtworks.xstream.core.util.Fields;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public final class XStreamImplicitState {
    private XStreamImplicitState() {
    }

    public static final class Location {
        public final String fieldName;
        public final Class<?> definedIn;

        public Location(final String fieldName, final Class<?> definedIn) {
            this.fieldName = fieldName;
            this.definedIn = definedIn;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = 7 * result + (definedIn == null ? 0 : definedIn.getName().hashCode());
            result = 7 * result + (fieldName == null ? 0 : fieldName.hashCode());
            return result;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final Location other = (Location) obj;
            if (definedIn != other.definedIn) {
                return false;
            }
            return !(fieldName == null ? other.fieldName != null : !fieldName.equals(other.fieldName));
        }
    }

    public static final class ArrayCollector extends ArrayList<Object> {
        private final Class<?> physicalFieldType;

        public ArrayCollector(final Class<?> physicalFieldType) {
            this.physicalFieldType = physicalFieldType;
        }

        public Object toPhysicalArray() {
            final Object[] objects = toArray();
            final Object array = Array.newInstance(physicalFieldType.getComponentType(), objects.length);
            if (physicalFieldType.getComponentType().isPrimitive()) {
                for (int i = 0; i < objects.length; i++) {
                    Array.set(array, i, Array.get(objects, i));
                }
            } else {
                System.arraycopy(objects, 0, array, 0, objects.length);
            }
            return array;
        }
    }

    public static final class MapCollector extends AbstractList<Object> {
        private final ReflectionProvider reflectionProvider;
        private final Map<Object, Object> map;
        private final String keyFieldName;
        private final Map<Class<?>, Field> fieldCache = new HashMap<>();

        public MapCollector(final ReflectionProvider reflectionProvider,
                            final Map<Object, Object> map, final String keyFieldName) {
            this.reflectionProvider = reflectionProvider;
            this.map = map;
            this.keyFieldName = keyFieldName;
        }

        @Override
        public boolean add(final Object object) {
            if (object == null) {
                final boolean containsNull = !map.containsKey(null);
                map.put(null, null);
                return containsNull;
            }
            final Class<?> itemType = object.getClass();
            if (keyFieldName != null) {
                Field field = fieldCache.get(itemType);
                if (field == null) {
                    field = reflectionProvider.getField(itemType, keyFieldName);
                    fieldCache.put(itemType, field);
                }
                if (field != null) {
                    final Object key = Fields.read(field, object);
                    return map.put(key, object) == null;
                }
            } else if (object instanceof Map.Entry) {
                final Map.Entry<?, ?> entry = (Map.Entry<?, ?>) object;
                return map.put(entry.getKey(), entry.getValue()) == null;
            }
            final ConversionException exception =
                    new ConversionException("Element  is not defined as entry for implicit map");
            exception.add("map-type", map.getClass().getName());
            exception.add("element-type", object.getClass().getName());
            throw exception;
        }

        @Override
        public Object get(final int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int size() {
            return map.size();
        }
    }
}
