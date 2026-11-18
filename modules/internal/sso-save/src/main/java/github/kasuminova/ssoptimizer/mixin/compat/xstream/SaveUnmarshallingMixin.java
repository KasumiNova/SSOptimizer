package github.kasuminova.ssoptimizer.mixin.compat.xstream;

import com.thoughtworks.xstream.converters.ConversionException;
import com.thoughtworks.xstream.core.util.HierarchicalStreams;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.mapper.Mapper;
import github.kasuminova.ssoptimizer.common.save.SavePermissions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.thoughtworks.xstream.core.TreeUnmarshaller", remap = false)
public abstract class SaveUnmarshallingMixin {
    @Shadow
    protected HierarchicalStreamReader reader;

    @Redirect(method = "convertAnother(Ljava/lang/Object;Ljava/lang/Class;Lcom/thoughtworks/xstream/converters/Converter;)Ljava/lang/Object;",
            at = @At(value = "INVOKE", target = "Lcom/thoughtworks/xstream/mapper/Mapper;defaultImplementationOf(Ljava/lang/Class;)Ljava/lang/Class;"),
            remap = false)
    private Class<?> ssoptimizer$checkDeclaredType(final Mapper mapper, final Class<?> type) {
        final Class<?> resolved = mapper.defaultImplementationOf(type);
        SavePermissions.check(mapper, resolved, reader);
        return resolved;
    }

    @Redirect(method = "start(Lcom/thoughtworks/xstream/converters/DataHolder;)Ljava/lang/Object;",
            at = @At(value = "INVOKE", target = "Lcom/thoughtworks/xstream/core/util/HierarchicalStreams;readClassType(Lcom/thoughtworks/xstream/io/HierarchicalStreamReader;Lcom/thoughtworks/xstream/mapper/Mapper;)Ljava/lang/Class;"),
            remap = false)
    private Class<?> ssoptimizer$reportRootType(final HierarchicalStreamReader input, final Mapper mapper) {
        try {
            return HierarchicalStreams.readClassType(input, mapper);
        } catch (ConversionException error) {
            error.add("xml-node", input.getNodeName());
            input.appendErrors(error);
            throw error;
        }
    }
}
