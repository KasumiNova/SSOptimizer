package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.mapper.Mapper;

import java.lang.reflect.Field;

/** 解析计划随 XStream flushCache 整体失效。 */
public final class XStreamUnmarshalPlan {
    public enum Kind {
        FIELD,
        UNCHECKED,
        ITEM,
        UNKNOWN,
        IMPLICIT
    }

    public final Kind kind;
    public final String fieldName;
    public final Field field;
    public final Class<?> defaultType;
    public final Converter localConverter;
    public final Class<?> itemType;
    public final Class<?> nodeClass;
    public final String nodeImplicitFieldName;
    public final Mapper.ImplicitCollectionMapping implicitMapping;

    private XStreamUnmarshalPlan(final Kind kind,
                                 final String fieldName,
                                 final Field field,
                                 final Class<?> defaultType,
                                 final Converter localConverter,
                                 final Class<?> itemType,
                                 final Class<?> nodeClass,
                                 final String nodeImplicitFieldName,
                                 final Mapper.ImplicitCollectionMapping implicitMapping) {
        this.kind = kind;
        this.fieldName = fieldName;
        this.field = field;
        this.defaultType = defaultType;
        this.localConverter = localConverter;
        this.itemType = itemType;
        this.nodeClass = nodeClass;
        this.nodeImplicitFieldName = nodeImplicitFieldName;
        this.implicitMapping = implicitMapping;
    }

    public static XStreamUnmarshalPlan field(final String fieldName, final Field field,
                                             final Class<?> defaultType, final Converter localConverter) {
        return new XStreamUnmarshalPlan(Kind.FIELD, fieldName, field, defaultType, localConverter,
                null, null, null, null);
    }

    public static XStreamUnmarshalPlan unchecked(final String fieldName) {
        return new XStreamUnmarshalPlan(Kind.UNCHECKED, fieldName, null, null, null, null, null, null, null);
    }

    public static XStreamUnmarshalPlan item(final String fieldName, final Class<?> itemType) {
        return new XStreamUnmarshalPlan(Kind.ITEM, fieldName, null, null, null, itemType, null, null, null);
    }

    public static XStreamUnmarshalPlan unknown(final String fieldName, final Class<?> nodeClass,
                                               final String nodeImplicitFieldName) {
        return new XStreamUnmarshalPlan(Kind.UNKNOWN, fieldName, null, null, null, null,
                nodeClass, nodeImplicitFieldName, null);
    }

    public static XStreamUnmarshalPlan implicit(final String fieldName,
                                                final Mapper.ImplicitCollectionMapping implicitMapping) {
        return new XStreamUnmarshalPlan(Kind.IMPLICIT, fieldName, null, null, null, null, null, null,
                implicitMapping);
    }
}
