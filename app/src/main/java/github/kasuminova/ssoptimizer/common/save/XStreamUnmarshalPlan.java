package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.mapper.Mapper;

import java.lang.reflect.Field;

/**
 * XStream 节点解析计划。
 * <p>
 * 读档时 {@code AbstractReflectionConverter.doUnmarshal} 对每个子节点重复执行
 * 一串只依赖 (声明类, 节点名) 的静态解析：字段名映射、隐式集合映射查询、字段查找与父类回退、
 * 字段可序列化校验、默认实现类型、局部转换器查询。本类将单次解析结果固化，
 * 后续同键节点只执行动态部分（class 属性读取、值转换、写字段、隐式集合累积）。
 * <p>
 * 计划由 {@code XStreamReflectionConverterMixin} 构建，随 {@code flushCache} 整体失效。
 */
public final class XStreamUnmarshalPlan {
    /**
     * 节点解析形态，对应原版 doUnmarshal 子节点循环的各分支。
     */
    public enum Kind {
        /** 命中已声明字段且通过可序列化校验（fast path，占绝大多数节点）。 */
        FIELD,
        /** 字段存在但未通过 shouldUnmarshalField / shouldSerializeMember 校验，节点整体跳过。 */
        UNCHECKED,
        /** 无字段，但映射为隐式集合项字段（getItemTypeForItemFieldName 命中）。 */
        ITEM,
        /** 无字段无项类型，按节点名解析为类（可能解析失败或需要 handleUnknownField）。 */
        UNKNOWN,
        /** 命中隐式集合映射（getImplicitCollectionDefForFieldName 命中）。 */
        IMPLICIT
    }

    public final Kind kind;
    /** 映射后的真实字段名（realMember 结果）。 */
    public final String fieldName;
    /** FIELD：父类回退后的最终字段。 */
    public final Field field;
    /** FIELD：字段类型的默认实现类（无 class 属性时使用）。 */
    public final Class<?> defaultType;
    /** FIELD：局部转换器（getLocalConverter 结果，可为 null）。 */
    public final Converter localConverter;
    /** ITEM：项字段类型。 */
    public final Class<?> itemType;
    /** UNKNOWN：按节点名解析出的类；null 表示解析失败（CannotResolveClassException）。 */
    public final Class<?> nodeClass;
    /** UNKNOWN：节点类对应的隐式字段名；null 表示需要 handleUnknownField。 */
    public final String nodeImplicitFieldName;
    /** IMPLICIT：隐式集合映射。 */
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
