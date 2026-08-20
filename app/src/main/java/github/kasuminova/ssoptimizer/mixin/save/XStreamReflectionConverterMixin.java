package github.kasuminova.ssoptimizer.mixin.save;

import com.thoughtworks.xstream.converters.ConversionException;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.SingleValueConverter;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.converters.reflection.AbstractReflectionConverter;
import com.thoughtworks.xstream.converters.reflection.ObjectAccessException;
import com.thoughtworks.xstream.converters.reflection.PureJavaReflectionProvider;
import com.thoughtworks.xstream.converters.reflection.ReflectionProvider;
import com.thoughtworks.xstream.core.ReferencingMarshallingContext;
import com.thoughtworks.xstream.core.util.ArrayIterator;
import com.thoughtworks.xstream.core.util.HierarchicalStreams;
import com.thoughtworks.xstream.core.util.MemberDictionary;
import com.thoughtworks.xstream.core.util.Primitives;
import com.thoughtworks.xstream.io.ExtendedHierarchicalStreamWriterHelper;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import com.thoughtworks.xstream.mapper.CannotResolveClassException;
import com.thoughtworks.xstream.mapper.Mapper;
import github.kasuminova.ssoptimizer.common.save.XStreamFieldAccessHelper;
import github.kasuminova.ssoptimizer.common.save.XStreamImplicitState;
import github.kasuminova.ssoptimizer.common.save.XStreamMarshalPlan;
import github.kasuminova.ssoptimizer.common.save.XStreamUnmarshalPlan;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XStream 反射转换器 doUnmarshal 解析计划缓存 Mixin。
 * <p>
 * 注入目标：{@code com.thoughtworks.xstream.converters.reflection.AbstractReflectionConverter}<br>
 * 注入动机：读档时 doUnmarshal 对每个子节点重复执行只依赖 (声明类, 节点名) 的静态解析
 * （字段名映射、隐式集合查询、字段查找与父类回退、序列化校验、默认实现类型、局部转换器），
 * 每次都要走完十余层 Mapper 包装链；同一 (类, 节点名) 组合在 72MB 存档中重复数十万次。<br>
 * 注入效果：整体覆写 doUnmarshal，静态解析结果按 (resultType, nodeName) 固化为
 * {@link XStreamUnmarshalPlan}，后续节点只执行动态部分（class 属性读取、值转换、
 * 写字段、seenFields 查重、隐式集合累积）。defined-in 属性路径与全部错误语义保持原版行为。<br>
 * 失效挂钩：{@code flushCache} 时清空计划缓存，与 XStream 自身缓存生命周期一致。
 */
@Mixin(targets = "com.thoughtworks.xstream.converters.reflection.AbstractReflectionConverter")
public abstract class XStreamReflectionConverterMixin {
    @Final
    @Shadow
    protected Mapper mapper;

    @Final
    @Shadow
    protected ReflectionProvider reflectionProvider;

    @Unique
    private final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, XStreamUnmarshalPlan>>
            ssoptimizer$unmarshalPlans = new ConcurrentHashMap<>();

    @Unique
    private PureJavaReflectionProvider ssoptimizer$pureJavaReflectionProvider;

    @Unique
    private final ConcurrentHashMap<Class<?>, XStreamMarshalPlan>
            ssoptimizer$marshalPlans = new ConcurrentHashMap<>();

    /**
     * serializedClass 的动态实际类型查询缓存（marshal 侧实际类型在计划外）。
     */
    @Unique
    private final ConcurrentHashMap<Class<?>, String>
            ssoptimizer$serializedClassCache = new ConcurrentHashMap<>();

    @Shadow
    protected abstract boolean shouldUnmarshalField(Field field);

    @Shadow
    private Class<?> readDeclaringClass(final HierarchicalStreamReader reader) {
        throw new AssertionError("Shadow method was not transformed");
    }

    @Shadow
    private void handleUnknownField(final Class<?> classDefiningField, final String fieldName,
                                    final Class<?> resultType, final String originalNodeName) {
        throw new AssertionError("Shadow method was not transformed");
    }

    /**
     * 覆写 doUnmarshal：静态解析结果计划化缓存。
     *
     * @param result  目标对象
     * @param reader  XML 读取器
     * @param context 反序列化上下文
     * @return 填充完成的对象
     * @author KasumiNova
     * @reason 逐节点全链静态解析是读档 unmarshal 阶段的主要 CPU 开销之一，
     * 计划缓存后每节点仅需一次 CHM 命中加动态转换。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Overwrite(remap = false)
    public Object doUnmarshal(final Object result, final HierarchicalStreamReader reader,
                              final UnmarshallingContext context) {
        // === 属性循环：与原版一致 ===
        final Class<?> resultType = result.getClass();
        final MemberDictionary seenFields = new MemberDictionary();
        final Iterator<String> it = reader.getAttributeNames();
        while (it.hasNext()) {
            final String attrAlias = it.next();
            final String attrName = mapper.realMember(resultType, mapper.attributeForAlias(attrAlias));
            final Field field = reflectionProvider.getFieldOrNull(resultType, attrName);
            if (field == null || !shouldUnmarshalField(field)
                    || !mapper.shouldSerializeMember(field.getDeclaringClass(), attrName)) {
                continue;
            }
            final SingleValueConverter converter =
                    mapper.getConverterFromAttribute(field.getDeclaringClass(), attrName, field.getType());
            Class<?> type = field.getType();
            if (converter == null) {
                continue;
            }
            final Object value = converter.fromString(reader.getAttribute(attrAlias));
            if (type.isPrimitive()) {
                type = Primitives.box(type);
            }
            if (value != null && !type.isAssignableFrom(value.getClass())) {
                final ConversionException exception = new ConversionException("Cannot convert type");
                exception.add("source-type", value.getClass().getName());
                exception.add("target-type", type.getName());
                throw exception;
            }
            if (!seenFields.add(field.getDeclaringClass(), attrName)) {
                throw new AbstractReflectionConverter.DuplicateFieldException(attrName);
            }
            reflectionProvider.writeField(result, attrName, value, field.getDeclaringClass());
        }

        // === 子节点循环：静态解析走计划缓存 ===
        Map<XStreamImplicitState.Location, Collection<Object>> implicitCollectionsForCurrentObject = null;
        final ConcurrentHashMap<String, XStreamUnmarshalPlan> plans = ssoptimizer$plansFor(resultType);
        while (reader.hasMoreChildren()) {
            reader.moveDown();
            final String originalNodeName = reader.getNodeName();
            final Class<?> explicitDeclaringClass = readDeclaringClass(reader);
            final Class<?> fieldDeclaringClass =
                    explicitDeclaringClass == null ? resultType : explicitDeclaringClass;

            Object value;
            Class<?> type = null;
            Field field = null;
            String fieldName;
            String implicitFieldName = null;

            if (explicitDeclaringClass == null) {
                // 快速路径：静态部分全部来自计划缓存
                final XStreamUnmarshalPlan plan =
                        plans.computeIfAbsent(originalNodeName, n -> ssoptimizer$resolvePlan(resultType, n));
                fieldName = plan.fieldName;
                switch (plan.kind) {
                    case FIELD: {
                        field = plan.field;
                        final String classAttribute = HierarchicalStreams.readClassAttribute(reader, mapper);
                        type = classAttribute != null ? mapper.realClass(classAttribute) : plan.defaultType;
                        // 等价于原版 unmarshallField：context.convertAnother(result, type, localConverter)
                        value = context.convertAnother(result, type, plan.localConverter);
                        final Class<?> definedType = field.getType();
                        if (!definedType.isPrimitive()) {
                            type = definedType;
                        }
                        break;
                    }
                    case UNCHECKED: {
                        // 字段未通过序列化校验，原版行为为整体跳过该节点
                        value = null;
                        break;
                    }
                    case ITEM: {
                        final String classAttribute = HierarchicalStreams.readClassAttribute(reader, mapper);
                        type = classAttribute != null ? mapper.realClass(classAttribute) : plan.itemType;
                        value = context.convertAnother(result, type);
                        break;
                    }
                    case UNKNOWN: {
                        type = plan.nodeClass;
                        implicitFieldName = plan.nodeImplicitFieldName;
                        if (type == null || implicitFieldName == null) {
                            handleUnknownField(null, plan.fieldName, resultType, originalNodeName);
                            type = null;
                        }
                        if (type == null) {
                            value = null;
                        } else if (Map.Entry.class.equals(type)) {
                            reader.moveDown();
                            final Object key =
                                    context.convertAnother(result, HierarchicalStreams.readClassType(reader, mapper));
                            reader.moveUp();
                            reader.moveDown();
                            final Object v =
                                    context.convertAnother(result, HierarchicalStreams.readClassType(reader, mapper));
                            reader.moveUp();
                            value = Collections.singletonMap(key, v).entrySet().iterator().next();
                        } else {
                            value = context.convertAnother(result, type);
                        }
                        break;
                    }
                    case IMPLICIT: {
                        implicitFieldName = plan.implicitMapping.getFieldName();
                        type = plan.implicitMapping.getItemType();
                        if (type == null) {
                            final String classAttribute = HierarchicalStreams.readClassAttribute(reader, mapper);
                            type = mapper.realClass(classAttribute != null ? classAttribute : originalNodeName);
                        }
                        value = context.convertAnother(result, type);
                        break;
                    }
                    default:
                        throw new IllegalStateException("Unknown unmarshal plan kind: " + plan.kind);
                }
            } else {
                // defined-in 属性路径：占比极低，完整执行原版慢速解析
                fieldName = mapper.realMember(fieldDeclaringClass, originalNodeName);
                final Mapper.ImplicitCollectionMapping implicitCollectionMapping =
                        mapper.getImplicitCollectionDefForFieldName(fieldDeclaringClass, fieldName);
                if (implicitCollectionMapping == null) {
                    field = reflectionProvider.getFieldOrNull(fieldDeclaringClass, fieldName);
                    if (field == null) {
                        final Class<?> itemType = mapper.getItemTypeForItemFieldName(fieldDeclaringClass, fieldName);
                        if (itemType != null) {
                            final String classAttribute = HierarchicalStreams.readClassAttribute(reader, mapper);
                            type = classAttribute != null ? mapper.realClass(classAttribute) : itemType;
                        } else {
                            try {
                                type = mapper.realClass(originalNodeName);
                                implicitFieldName = mapper.getFieldNameForItemTypeAndName(
                                        fieldDeclaringClass, type, originalNodeName);
                            } catch (final CannotResolveClassException ignored) {
                                // 原版行为：节点名不可解析时进入 handleUnknownField 路径，type 保持 null
                            }
                            if (type == null || implicitFieldName == null) {
                                handleUnknownField(explicitDeclaringClass, fieldName,
                                        fieldDeclaringClass, originalNodeName);
                                type = null;
                            }
                        }
                        if (type == null) {
                            value = null;
                        } else if (Map.Entry.class.equals(type)) {
                            reader.moveDown();
                            final Object key =
                                    context.convertAnother(result, HierarchicalStreams.readClassType(reader, mapper));
                            reader.moveUp();
                            reader.moveDown();
                            final Object v =
                                    context.convertAnother(result, HierarchicalStreams.readClassType(reader, mapper));
                            reader.moveUp();
                            value = Collections.singletonMap(key, v).entrySet().iterator().next();
                        } else {
                            value = context.convertAnother(result, type);
                        }
                    } else {
                        if (shouldUnmarshalField(field)
                                && mapper.shouldSerializeMember(field.getDeclaringClass(), fieldName)) {
                            final String classAttribute = HierarchicalStreams.readClassAttribute(reader, mapper);
                            type = classAttribute != null
                                    ? mapper.realClass(classAttribute)
                                    : mapper.defaultImplementationOf(field.getType());
                            value = context.convertAnother(result, type,
                                    mapper.getLocalConverter(field.getDeclaringClass(), field.getName()));
                            final Class<?> definedType = field.getType();
                            if (!definedType.isPrimitive()) {
                                type = definedType;
                            }
                        } else {
                            value = null;
                        }
                    }
                } else {
                    implicitFieldName = implicitCollectionMapping.getFieldName();
                    type = implicitCollectionMapping.getItemType();
                    if (type == null) {
                        final String classAttribute = HierarchicalStreams.readClassAttribute(reader, mapper);
                        type = mapper.realClass(classAttribute != null ? classAttribute : originalNodeName);
                    }
                    value = context.convertAnother(result, type);
                }
            }

            // === 公共尾部：与原版一致 ===
            if (value != null && !type.isAssignableFrom(value.getClass())) {
                throw new ConversionException(
                        "Cannot convert type " + value.getClass().getName() + " to type " + type.getName());
            }
            if (field != null) {
                reflectionProvider.writeField(result, fieldName, value, field.getDeclaringClass());
                if (!seenFields.add(field.getDeclaringClass(), fieldName)) {
                    throw new AbstractReflectionConverter.DuplicateFieldException(fieldName);
                }
            } else if (type != null) {
                if (implicitFieldName == null) {
                    implicitFieldName = mapper.getFieldNameForItemTypeAndName(fieldDeclaringClass,
                            value != null ? value.getClass() : Mapper.Null.class, originalNodeName);
                }
                if (implicitCollectionsForCurrentObject == null) {
                    implicitCollectionsForCurrentObject = new HashMap<>();
                }
                ssoptimizer$writeValueToImplicitCollection(value, implicitCollectionsForCurrentObject, result,
                        new XStreamImplicitState.Location(implicitFieldName, fieldDeclaringClass));
            }
            reader.moveUp();
        }

        // === 隐式数组字段固化：与原版一致 ===
        if (implicitCollectionsForCurrentObject != null) {
            for (final Map.Entry<XStreamImplicitState.Location, Collection<Object>> entry
                    : implicitCollectionsForCurrentObject.entrySet()) {
                final Object collected = entry.getValue();
                if (!(collected instanceof XStreamImplicitState.ArrayCollector)) {
                    continue;
                }
                final Object array = ((XStreamImplicitState.ArrayCollector) collected).toPhysicalArray();
                final XStreamImplicitState.Location location = entry.getKey();
                final Field arrayField = reflectionProvider.getFieldOrNull(location.definedIn, location.fieldName);
                reflectionProvider.writeField(result, location.fieldName, array,
                        arrayField != null ? arrayField.getDeclaringClass() : null);
            }
        }
        return result;
    }

    @Inject(method = "flushCache", at = @At("HEAD"), remap = false)
    private void ssoptimizer$clearUnmarshalPlansOnFlush(final CallbackInfo ci) {
        ssoptimizer$unmarshalPlans.clear();
        ssoptimizer$marshalPlans.clear();
        ssoptimizer$serializedClassCache.clear();
    }

    /**
     * 覆写 doMarshal：字段级静态解析结果计划化缓存。
     *
     * @param source  待序列化对象
     * @param writer  XML 写出器
     * @param context 序列化上下文
     * @author KasumiNova
     * @reason 原版对每个对象重复 visitSerializableFields 全字段遍历与全 Mapper 链静态解析，
     * 并伴随 FieldInfo/defaultFieldDefinition/writtenAttributes 逐对象分配；
     * 计划缓存后每对象只做字段值读取与写出。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Overwrite(remap = false)
    protected void doMarshal(final Object source, final HierarchicalStreamWriter writer,
                             final MarshallingContext context) {
        final Class<?> sourceType = source.getClass();
        final XStreamMarshalPlan plan = ssoptimizer$marshalPlans.computeIfAbsent(
                sourceType, t -> ssoptimizer$buildMarshalPlan(t, source));

        // === 属性阶段：与原版 visit 期间的属性写出一致 ===
        Set<String> writtenAttributes = plan.dupAttrFieldNames != null ? new HashSet<>() : null;
        for (final XStreamMarshalPlan.AttrEntry entry : plan.attrEntries) {
            final Object value = XStreamFieldAccessHelper.read(entry.field, source);
            if (value != null) {
                if (writtenAttributes != null && writtenAttributes.contains(entry.fieldName)) {
                    final ConversionException exception = new ConversionException(
                            "Cannot write field as attribute for object, attribute name already in use");
                    exception.add("field-name", entry.fieldName);
                    exception.add("object-type", sourceType.getName());
                    throw exception;
                }
                final String str = entry.converter.toString(value);
                if (str != null) {
                    writer.addAttribute(entry.attributeAlias, str);
                }
            }
            if (writtenAttributes != null) {
                writtenAttributes.add(entry.fieldName);
            }
        }

        // === 元素阶段 ===
        for (final XStreamMarshalPlan.ElemEntry entry : plan.elemEntries) {
            final Object value = XStreamFieldAccessHelper.read(entry.field, source);
            if (value == null) {
                continue;
            }
            if (entry.implicitMapping != null) {
                ssoptimizer$marshalImplicitCollection(writer, context, plan, entry, value);
                continue;
            }
            ssoptimizer$writeFieldElement(writer, context, plan, entry,
                    entry.serializedMemberName, entry.type, value);
        }
    }

    /**
     * 等价于原版匿名 FieldMarshaller.writeField：写单个字段元素节点。
     * fieldType 为 entry.type 时全部静态量来自计划；隐式集合逐项调用时 fieldType 为动态项类型。
     */
    @Unique
    private void ssoptimizer$writeFieldElement(final HierarchicalStreamWriter writer,
                                               final MarshallingContext context,
                                               final XStreamMarshalPlan plan,
                                               final XStreamMarshalPlan.ElemEntry entry,
                                               final String nodeName, final Class<?> fieldType,
                                               final Object value) {
        final Class<?> actualType = value != null ? value.getClass() : fieldType;
        ExtendedHierarchicalStreamWriterHelper.startNode(writer, nodeName, actualType);
        if (value != null) {
            final Class<?> defaultType;
            final String defaultTypeName;
            if (fieldType == entry.type) {
                defaultType = entry.defaultType;
                defaultTypeName = entry.defaultTypeSerializedName;
            } else {
                defaultType = mapper.defaultImplementationOf(fieldType);
                defaultTypeName = ssoptimizer$serializedClass(defaultType);
            }
            if (!actualType.equals(defaultType)) {
                final String serializedClassName = ssoptimizer$serializedClass(actualType);
                if (!serializedClassName.equals(defaultTypeName) && plan.classAttrName != null) {
                    writer.addAttribute(plan.classAttrName, serializedClassName);
                }
            }
            if (entry.definedInAttrValue != null && plan.definedInAttrName != null) {
                writer.addAttribute(plan.definedInAttrName, entry.definedInAttrValue);
            }
            // 等价于原版 marshallField：context.convertAnother(value, localConverter)
            context.convertAnother(value, entry.localConverter);
        }
        writer.endNode();
    }

    /**
     * 等价于原版匿名 FieldMarshaller.writeItem：隐式集合内 Map.Entry 的键/值项。
     */
    @Unique
    private void ssoptimizer$writeImplicitItem(final HierarchicalStreamWriter writer,
                                               final MarshallingContext context,
                                               final XStreamMarshalPlan plan, final Object item) {
        if (item == null) {
            ExtendedHierarchicalStreamWriterHelper.startNode(writer, plan.nullTypeName, Mapper.Null.class);
            writer.endNode();
        } else {
            ExtendedHierarchicalStreamWriterHelper.startNode(
                    writer, ssoptimizer$serializedClass(item.getClass()), item.getClass());
            context.convertAnother(item);
            writer.endNode();
        }
    }

    /**
     * 等价复制原版 doMarshal 尾部的隐式集合逐项写出逻辑。
     */
    @Unique
    private void ssoptimizer$marshalImplicitCollection(final HierarchicalStreamWriter writer,
                                                       final MarshallingContext context,
                                                       final XStreamMarshalPlan plan,
                                                       final XStreamMarshalPlan.ElemEntry entry,
                                                       final Object value) {
        final Mapper.ImplicitCollectionMapping mapping = entry.implicitMapping;
        if (context instanceof ReferencingMarshallingContext
                && value != Collections.EMPTY_LIST && value != Collections.EMPTY_SET
                && value != Collections.EMPTY_MAP) {
            ((ReferencingMarshallingContext) context).registerImplicit(value);
        }
        final boolean isCollection = value instanceof Collection;
        final boolean isMap = value instanceof Map;
        final boolean isEntry = isMap && mapping.getKeyFieldName() == null;
        final boolean isArray = value.getClass().isArray();
        final Iterator<?> iter = isArray ? new ArrayIterator(value)
                : isCollection ? ((Collection<?>) value).iterator()
                : isEntry ? ((Map<?, ?>) value).entrySet().iterator()
                : ((Map<?, ?>) value).values().iterator();
        while (iter.hasNext()) {
            final Object obj = iter.next();
            final String itemName;
            final Class<?> itemType;
            if (obj == null) {
                itemType = Object.class;
                itemName = plan.nullTypeName;
            } else {
                if (isEntry) {
                    final String entryName = mapping.getItemFieldName() != null
                            ? mapping.getItemFieldName()
                            : ssoptimizer$serializedClass(Map.Entry.class);
                    final Map.Entry<?, ?> mapEntry = (Map.Entry<?, ?>) obj;
                    ExtendedHierarchicalStreamWriterHelper.startNode(writer, entryName, mapEntry.getClass());
                    ssoptimizer$writeImplicitItem(writer, context, plan, mapEntry.getKey());
                    ssoptimizer$writeImplicitItem(writer, context, plan, mapEntry.getValue());
                    writer.endNode();
                    continue;
                }
                if (mapping.getItemFieldName() != null) {
                    itemType = mapping.getItemType();
                    itemName = mapping.getItemFieldName();
                } else {
                    itemType = obj.getClass();
                    itemName = ssoptimizer$serializedClass(itemType);
                }
            }
            ssoptimizer$writeFieldElement(writer, context, plan, entry, itemName, itemType, obj);
        }
    }

    /**
     * 构建类的序列化计划：以首个实例执行一次原版 visitSerializableFields 遍历，
     * 固化全部静态解析结果；隐式集合同名去重（原版 hiddenMappers 语义）在此静态完成。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Unique
    private XStreamMarshalPlan ssoptimizer$buildMarshalPlan(final Class<?> sourceType,
                                                            final Object firstSource) {
        final List<XStreamMarshalPlan.AttrEntry> attrs = new ArrayList<>();
        final List<XStreamMarshalPlan.ElemEntry> elems = new ArrayList<>();
        final Map<String, Field> defaultFieldDefinition = new HashMap<>();
        final Map<String, int[]> attrNameCounts = new HashMap<>();
        reflectionProvider.visitSerializableFields(firstSource, new ReflectionProvider.Visitor() {
            @Override
            public void visit(final String fieldName, final Class type, final Class definedIn,
                              final Object value) {
                if (!mapper.shouldSerializeMember(definedIn, fieldName)) {
                    return;
                }
                if (!defaultFieldDefinition.containsKey(fieldName)) {
                    Class<?> lookupType = sourceType;
                    if (definedIn != sourceType && !mapper.shouldSerializeMember(lookupType, fieldName)) {
                        lookupType = definedIn;
                    }
                    defaultFieldDefinition.put(fieldName, reflectionProvider.getField(lookupType, fieldName));
                }
                final SingleValueConverter converter =
                        mapper.getConverterFromItemType(fieldName, type, definedIn);
                if (converter != null) {
                    attrs.add(new XStreamMarshalPlan.AttrEntry(fieldName,
                            mapper.aliasForAttribute(mapper.serializedMember(definedIn, fieldName)),
                            converter, reflectionProvider.getField(definedIn, fieldName)));
                    attrNameCounts.computeIfAbsent(fieldName, k -> new int[1])[0]++;
                } else {
                    final Field field = reflectionProvider.getField(definedIn, fieldName);
                    final Field defaultField = defaultFieldDefinition.get(fieldName);
                    final Class<?> defaultType = mapper.defaultImplementationOf(type);
                    elems.add(new XStreamMarshalPlan.ElemEntry(fieldName, type, definedIn, field,
                            defaultField,
                            mapper.getLocalConverter(definedIn, fieldName),
                            mapper.serializedMember(sourceType, fieldName),
                            defaultType, mapper.serializedClass(defaultType),
                            defaultField.getDeclaringClass() != definedIn
                                    ? mapper.serializedClass(definedIn) : null,
                            mapper.getImplicitCollectionDefForFieldName(
                                    defaultField.getDeclaringClass() == definedIn ? sourceType : definedIn,
                                    fieldName)));
                }
            }
        });
        // 原版 hiddenMappers 语义：同名字段的重复隐式映射回落为普通字段写出
        final Map<String, Set<Mapper.ImplicitCollectionMapping>> seenMappings = new HashMap<>();
        for (final XStreamMarshalPlan.ElemEntry entry : elems) {
            if (entry.implicitMapping == null) {
                continue;
            }
            final Set<Mapper.ImplicitCollectionMapping> seen =
                    seenMappings.computeIfAbsent(entry.fieldName, k -> new HashSet<>());
            if (!seen.add(entry.implicitMapping)) {
                entry.implicitMapping = null;
            }
        }
        Set<String> dupAttrFieldNames = null;
        for (final Map.Entry<String, int[]> count : attrNameCounts.entrySet()) {
            if (count.getValue()[0] > 1) {
                if (dupAttrFieldNames == null) {
                    dupAttrFieldNames = new HashSet<>();
                }
                dupAttrFieldNames.add(count.getKey());
            }
        }
        return new XStreamMarshalPlan(attrs, elems,
                mapper.aliasForSystemAttribute("class"),
                mapper.aliasForSystemAttribute("defined-in"),
                mapper.serializedClass(null),
                dupAttrFieldNames);
    }

    /**
     * serializedClass 动态查询缓存（实际值类型在计划外，按类固化）。
     */
    @Unique
    private String ssoptimizer$serializedClass(final Class<?> type) {
        return ssoptimizer$serializedClassCache.computeIfAbsent(type, mapper::serializedClass);
    }


    /**
     * 取目标类的节点计划表，不存在时创建。
     */
    @Unique
    private ConcurrentHashMap<String, XStreamUnmarshalPlan> ssoptimizer$plansFor(final Class<?> resultType) {
        return ssoptimizer$unmarshalPlans.computeIfAbsent(resultType, k -> new ConcurrentHashMap<>());
    }

    /**
     * 构建 (声明类, 节点名) 的静态解析计划，语义与原版 doUnmarshal 子节点解析段一一对应。
     */
    @Unique
    private XStreamUnmarshalPlan ssoptimizer$resolvePlan(final Class<?> resultType, final String nodeName) {
        final String fieldName = mapper.realMember(resultType, nodeName);
        final Mapper.ImplicitCollectionMapping implicitMapping =
                mapper.getImplicitCollectionDefForFieldName(resultType, fieldName);
        if (implicitMapping != null) {
            return XStreamUnmarshalPlan.implicit(fieldName, implicitMapping);
        }
        Field field = reflectionProvider.getFieldOrNull(resultType, fieldName);
        if (field == null) {
            final Class<?> itemType = mapper.getItemTypeForItemFieldName(resultType, fieldName);
            if (itemType != null) {
                return XStreamUnmarshalPlan.item(fieldName, itemType);
            }
            Class<?> nodeClass = null;
            String nodeImplicitFieldName = null;
            try {
                nodeClass = mapper.realClass(nodeName);
                nodeImplicitFieldName =
                        mapper.getFieldNameForItemTypeAndName(resultType, nodeClass, nodeName);
            } catch (final CannotResolveClassException ignored) {
                // 原版行为：节点名不可解析时进入 handleUnknownField 路径，type 保持 null
            }
            return XStreamUnmarshalPlan.unknown(fieldName, nodeClass, nodeImplicitFieldName);
        }
        // 与原版一致：显式声明类缺失时沿父类链回退查找可序列化字段
        while (field != null && !(shouldUnmarshalField(field)
                && mapper.shouldSerializeMember(field.getDeclaringClass(), fieldName))) {
            field = reflectionProvider.getFieldOrNull(field.getDeclaringClass().getSuperclass(), fieldName);
        }
        if (field == null) {
            return XStreamUnmarshalPlan.unchecked(fieldName);
        }
        return XStreamUnmarshalPlan.field(fieldName, field,
                mapper.defaultImplementationOf(field.getType()),
                mapper.getLocalConverter(field.getDeclaringClass(), fieldName));
    }

    /**
     * 隐式集合累积，等价复制原版私有 writeValueToImplicitCollection
     * （原版依赖 private 内部类 FieldLocation/ArraysList/MappingList，编译期不可引用，
     * 故以 {@link XStreamImplicitState} 中等价实现替代）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Unique
    private void ssoptimizer$writeValueToImplicitCollection(
            final Object value, final Map<XStreamImplicitState.Location, Collection<Object>> implicitCollections,
            final Object result, final XStreamImplicitState.Location location) {
        Collection<Object> collection = implicitCollections.get(location);
        if (collection == null) {
            final Field field = reflectionProvider.getFieldOrNull(location.definedIn, location.fieldName);
            final Class<?> physicalFieldType =
                    field != null ? field.getType() : reflectionProvider.getFieldType(result, location.fieldName, null);
            if (physicalFieldType.isArray()) {
                collection = new XStreamImplicitState.ArrayCollector(physicalFieldType);
            } else {
                final Class<?> fieldType = mapper.defaultImplementationOf(physicalFieldType);
                if (!Collection.class.isAssignableFrom(fieldType) && !Map.class.isAssignableFrom(fieldType)) {
                    final ObjectAccessException oaex = new ObjectAccessException(
                            "Field is configured for an implicit Collection or Map, but is of an incompatible type");
                    oaex.add("field", result.getClass().getName() + "." + location.fieldName);
                    oaex.add("field-type", fieldType.getName());
                    throw oaex;
                }
                if (ssoptimizer$pureJavaReflectionProvider == null) {
                    ssoptimizer$pureJavaReflectionProvider = new PureJavaReflectionProvider();
                }
                final Object instance = ssoptimizer$pureJavaReflectionProvider.newInstance(fieldType);
                if (instance instanceof Collection) {
                    collection = (Collection<Object>) instance;
                } else {
                    final Mapper.ImplicitCollectionMapping implicitCollectionMapping =
                            mapper.getImplicitCollectionDefForFieldName(location.definedIn, location.fieldName);
                    collection = new XStreamImplicitState.MapCollector(reflectionProvider,
                            (Map) instance, implicitCollectionMapping.getKeyFieldName());
                }
                reflectionProvider.writeField(result, location.fieldName, instance,
                        field != null ? field.getDeclaringClass() : null);
            }
            implicitCollections.put(location, collection);
        }
        collection.add(value);
    }
}
