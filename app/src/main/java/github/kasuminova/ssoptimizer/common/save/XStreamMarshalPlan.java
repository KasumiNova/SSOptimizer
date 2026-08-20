package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.SingleValueConverter;
import com.thoughtworks.xstream.mapper.Mapper;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

/**
 * XStream 节点序列化计划。
 * <p>
 * 原版 {@code AbstractReflectionConverter.doMarshal} 对每个对象都执行一次
 * {@code visitSerializableFields} 全字段遍历，并对每个字段重复一串只依赖类的静态解析：
 * shouldSerializeMember 校验、默认字段定义推导、attribute 转换器查询、
 * serializedMember / serializedClass / defaultImplementationOf / getLocalConverter 等
 * 全 Mapper 包装链调用，还伴随 FieldInfo / defaultFieldDefinition / writtenAttributes
 * 等每对象分配。本类把单次遍历的静态结果固化，后续同类型对象只做值读取与写出。
 * <p>
 * 计划由 {@code XStreamReflectionConverterMixin} 构建，随 {@code flushCache} 整体失效。
 */
public final class XStreamMarshalPlan {
    /**
     * 属性字段条目（写出为 XML attribute）。
     */
    public static final class AttrEntry {
        public final String fieldName;
        /** aliasForAttribute(serializedMember(definedIn, fieldName)) 的固化结果。 */
        public final String attributeAlias;
        public final SingleValueConverter converter;
        public final Field field;

        public AttrEntry(final String fieldName, final String attributeAlias,
                         final SingleValueConverter converter, final Field field) {
            this.fieldName = fieldName;
            this.attributeAlias = attributeAlias;
            this.converter = converter;
            this.field = field;
        }
    }

    /**
     * 元素字段条目（写出为子节点）。
     */
    public static final class ElemEntry {
        public final String fieldName;
        public final Class<?> type;
        public final Class<?> definedIn;
        /** definedIn 内声明的字段本体，用于值读取与 marshallField。 */
        public final Field field;
        /** 原版 defaultFieldDefinition 推导结果。 */
        public final Field defaultField;
        /** getLocalConverter(definedIn, fieldName) 的固化结果，可为 null。 */
        public final Converter localConverter;
        /** serializedMember(sourceType, fieldName) 的固化结果。 */
        public final String serializedMemberName;
        /** defaultImplementationOf(fieldType) 的固化结果。 */
        public final Class<?> defaultType;
        /** serializedClass(defaultType) 的固化结果。 */
        public final String defaultTypeSerializedName;
        /** 需要写 defined-in 属性时为 serializedClass(definedIn)，否则为 null。 */
        public final String definedInAttrValue;
        /** 隐式集合映射；同名条目去重后被置 null 表示按普通字段写出。 */
        public Mapper.ImplicitCollectionMapping implicitMapping;

        public ElemEntry(final String fieldName, final Class<?> type, final Class<?> definedIn,
                         final Field field, final Field defaultField, final Converter localConverter,
                         final String serializedMemberName, final Class<?> defaultType,
                         final String defaultTypeSerializedName, final String definedInAttrValue,
                         final Mapper.ImplicitCollectionMapping implicitMapping) {
            this.fieldName = fieldName;
            this.type = type;
            this.definedIn = definedIn;
            this.field = field;
            this.defaultField = defaultField;
            this.localConverter = localConverter;
            this.serializedMemberName = serializedMemberName;
            this.defaultType = defaultType;
            this.defaultTypeSerializedName = defaultTypeSerializedName;
            this.definedInAttrValue = definedInAttrValue;
            this.implicitMapping = implicitMapping;
        }
    }

    /** 属性条目，按 visitSerializableFields 访问序。 */
    public final List<AttrEntry> attrEntries;
    /** 元素条目，按 visitSerializableFields 访问序。 */
    public final List<ElemEntry> elemEntries;
    /** aliasForSystemAttribute("class")，可为 null。 */
    public final String classAttrName;
    /** aliasForSystemAttribute("defined-in")，可为 null。 */
    public final String definedInAttrName;
    /** serializedClass(null)，隐式集合空元素节点名。 */
    public final String nullTypeName;
    /**
     * 存在同名属性字段（字段遮蔽）时的重名集合；非 null 时按原版语义做逐对象重名检查。
     * 绝大多数类为 null，完全跳过 writtenAttributes 集合分配。
     */
    public final Set<String> dupAttrFieldNames;

    public XStreamMarshalPlan(final List<AttrEntry> attrEntries, final List<ElemEntry> elemEntries,
                              final String classAttrName, final String definedInAttrName,
                              final String nullTypeName, final Set<String> dupAttrFieldNames) {
        this.attrEntries = attrEntries;
        this.elemEntries = elemEntries;
        this.classAttrName = classAttrName;
        this.definedInAttrName = definedInAttrName;
        this.nullTypeName = nullTypeName;
        this.dupAttrFieldNames = dupAttrFieldNames;
    }
}
