package github.kasuminova.ssoptimizer.mixin.save;

import com.thoughtworks.xstream.converters.ConverterLookup;
import com.thoughtworks.xstream.core.AbstractReferenceMarshaller;
import com.thoughtworks.xstream.core.ReferenceByIdMarshaller;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import com.thoughtworks.xstream.io.path.Path;
import com.thoughtworks.xstream.mapper.Mapper;
import github.kasuminova.ssoptimizer.common.save.XStreamReferenceIdHelper;
import org.apache.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

// 继承直接父类以访问 TreeMarshaller 的 protected writer 与 getMapper()，mapper 字段为 private，@Shadow 无法解析。
@Mixin(targets = "com.thoughtworks.xstream.core.ReferenceByIdMarshaller")
public abstract class XStreamReferenceByIdMarshallerMixin extends AbstractReferenceMarshaller {
    @Unique
    private static final Logger SSOPTIMIZER_LOGGER = Logger.getLogger(XStreamReferenceByIdMarshallerMixin.class);

    @Shadow(remap = false)
    private ReferenceByIdMarshaller.IDGenerator idGenerator;

    @Unique
    private boolean ssoptimizer$optimizedSequenceInitialized;

    @Unique
    private int ssoptimizer$nextReferenceId;

    @Unique
    private boolean ssoptimizer$idAliasInitialized;

    @Unique
    private String ssoptimizer$idAttributeAlias;

    protected XStreamReferenceByIdMarshallerMixin(final HierarchicalStreamWriter writer,
                                                  final ConverterLookup converterLookup,
                                                  final Mapper mapper) {
        super(writer, converterLookup, mapper);
    }

    @Overwrite(remap = false)
    protected Object createReferenceKey(final Path path,
                                        final Object item) {
        final ReferenceByIdMarshaller.IDGenerator generator = idGenerator;
        if (XStreamReferenceIdHelper.supportsOptimizedIds(generator)) {
            if (!ssoptimizer$optimizedSequenceInitialized) {
                ssoptimizer$nextReferenceId = ((XStreamSequenceGeneratorAccessor) generator).ssoptimizer$getCounter();
                ssoptimizer$optimizedSequenceInitialized = true;
            }
            return XStreamReferenceIdHelper.nextReferenceId(ssoptimizer$nextReferenceId++);
        }
        return generator.next(item);
    }

    @Overwrite(remap = false)
    protected String createReference(final Path path,
                                     final Object item) {
        return item instanceof String string ? string : item.toString();
    }

    @Overwrite(remap = false)
    protected void fireValidReference(final Object item) {
        if (!ssoptimizer$idAliasInitialized) {
            final Mapper currentMapper = getMapper();
            if (currentMapper == null) {
                throw ssoptimizer$referenceAttributeFailure("TreeMarshaller.mapper 为 null", item, null);
            }

            final String alias;
            try {
                alias = currentMapper.aliasForSystemAttribute("id");
            } catch (final RuntimeException e) {
                throw ssoptimizer$referenceAttributeFailure(
                        "Mapper 解析系统属性 id 失败, mapper=" + currentMapper.getClass().getName(), item, e);
            }
            if (alias == null) {
                throw ssoptimizer$referenceAttributeFailure(
                        "Mapper 未提供系统属性 id 的别名, mapper=" + currentMapper.getClass().getName(), item, null);
            }
            ssoptimizer$idAttributeAlias = alias;
            ssoptimizer$idAliasInitialized = true;
        }
        if (writer == null) {
            throw ssoptimizer$referenceAttributeFailure("TreeMarshaller.writer 为 null", item, null);
        }
        writer.addAttribute(ssoptimizer$idAttributeAlias, item.toString());
    }

    @Unique
    private IllegalStateException ssoptimizer$referenceAttributeFailure(final String reason,
                                                                        final Object item,
                                                                        final Throwable cause) {
        final String itemType = item == null ? "null" : item.getClass().getName();
        final IllegalStateException failure = new IllegalStateException(reason + ", itemType=" + itemType, cause);
        SSOPTIMIZER_LOGGER.error("[SSOptimizer] XStream 引用 ID 属性处理失败: " + failure.getMessage(), failure);
        return failure;
    }
}
