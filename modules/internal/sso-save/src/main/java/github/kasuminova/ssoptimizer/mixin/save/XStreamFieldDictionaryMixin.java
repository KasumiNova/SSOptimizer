package github.kasuminova.ssoptimizer.mixin.save;

import com.thoughtworks.xstream.converters.reflection.FieldKey;
import com.thoughtworks.xstream.converters.reflection.MissingFieldException;
import github.kasuminova.ssoptimizer.common.save.XStreamFieldDictionaryLookupCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.Map;

@Mixin(targets = "com.thoughtworks.xstream.converters.reflection.FieldDictionary")
public abstract class XStreamFieldDictionaryMixin {
    @Unique
    private final XStreamFieldDictionaryLookupCache ssoptimizer$lookupCache = new XStreamFieldDictionaryLookupCache();

    @Invoker("buildMap")
    protected abstract Map<?, Field> ssoptimizer$invokeBuildMap(Class<?> type, boolean keyedByFieldKey);

    @Overwrite(remap = false)
    public Field fieldOrNull(final Class<?> type, final String fieldName, final Class<?> definedIn) {
        return ssoptimizer$lookupCache.getOrResolve(type, fieldName, definedIn, () -> {
            final boolean typedLookup = definedIn != null;
            final Map<?, Field> fields = ssoptimizer$invokeBuildMap(type, typedLookup);
            final Object queryKey = typedLookup ? new FieldKey(fieldName, definedIn, -1) : fieldName;
            return fields.get(queryKey);
        });
    }

    @Overwrite(remap = false)
    public Field field(final Class<?> type, final String fieldName, final Class<?> definedIn) {
        final Field field = fieldOrNull(type, fieldName, definedIn);
        if (field == null) {
            throw new MissingFieldException(type.getName(), fieldName);
        }
        return field;
    }

    @Inject(method = "flushCache", at = @At("HEAD"), remap = false)
    private void ssoptimizer$clearLookupCache(final CallbackInfo callbackInfo) {
        ssoptimizer$lookupCache.clear();
    }
}