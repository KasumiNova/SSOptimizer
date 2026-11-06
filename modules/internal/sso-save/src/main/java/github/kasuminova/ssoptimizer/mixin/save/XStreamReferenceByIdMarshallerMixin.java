package github.kasuminova.ssoptimizer.mixin.save;

import com.thoughtworks.xstream.core.ReferenceByIdMarshaller;
import com.thoughtworks.xstream.io.path.Path;
import github.kasuminova.ssoptimizer.common.save.XStreamReferenceIdHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(targets = "com.thoughtworks.xstream.core.ReferenceByIdMarshaller")
public abstract class XStreamReferenceByIdMarshallerMixin {
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

    @Overwrite(remap = false)
    protected Object createReferenceKey(final Path path,
                                        final Object item) {
        final ReferenceByIdMarshaller.IDGenerator generator = idGenerator;
        if (XStreamReferenceIdHelper.supportsOptimizedIds(generator)) {
            if (!ssoptimizer$optimizedSequenceInitialized) {
                ssoptimizer$nextReferenceId = XStreamReferenceIdHelper.readSequenceCounter(generator);
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
            ssoptimizer$idAttributeAlias = XStreamReferenceIdHelper.resolveIdAttributeAlias(this);
            ssoptimizer$idAliasInitialized = true;
        }
        if (ssoptimizer$idAttributeAlias == null) {
            return;
        }
        XStreamReferenceIdHelper.writeReferenceIdAttribute(this, ssoptimizer$idAttributeAlias, item);
    }
}