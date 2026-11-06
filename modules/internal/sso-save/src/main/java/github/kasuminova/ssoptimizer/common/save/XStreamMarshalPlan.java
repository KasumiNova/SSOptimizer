package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.SingleValueConverter;
import com.thoughtworks.xstream.mapper.Mapper;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

/** 序列化计划随 XStream flushCache 整体失效。 */
public final class XStreamMarshalPlan {
    public static final class AttrEntry {
        public final String fieldName;
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

    public static final class ElemEntry {
        public final String fieldName;
        public final Class<?> type;
        public final Class<?> definedIn;
        public final Field field;
        public final Field defaultField;
        public final Converter localConverter;
        public final String serializedMemberName;
        public final Class<?> defaultType;
        public final String defaultTypeSerializedName;
        public final String definedInAttrValue;
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

    public final List<AttrEntry> attrEntries;
    public final List<ElemEntry> elemEntries;
    public final String classAttrName;
    public final String definedInAttrName;
    public final String nullTypeName;
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
