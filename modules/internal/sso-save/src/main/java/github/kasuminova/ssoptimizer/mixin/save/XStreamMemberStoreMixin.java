package github.kasuminova.ssoptimizer.mixin.save;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(targets = "com.thoughtworks.xstream.core.util.MemberStore")
public abstract class XStreamMemberStoreMixin {
    @Final
    @Mutable
    @Shadow
    private Map types;

    @Unique
    private final Map<String, Object> ssoptimizer$nullClassStore = new ConcurrentHashMap<>();

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ssoptimizer$replaceStoreWithConcurrentMap(final CallbackInfo ci) {
        this.types = new ConcurrentHashMap();
    }

    @SuppressWarnings("unchecked")
    @Overwrite(remap = false)
    public Object get(final Class definedIn, final String member) {
        if (definedIn == null) {
            return ssoptimizer$nullClassStore.get(member);
        }
        final Map<String, Object> store = (Map<String, Object>) this.types.get(definedIn.getName());
        return store == null ? null : store.get(member);
    }

    @SuppressWarnings("unchecked")
    @Overwrite(remap = false)
    public Object put(final Class definedIn, final String member, final Object value) {
        if (definedIn == null) {
            return ssoptimizer$nullClassStore.put(member, value);
        }
        final Map<String, Object> store = (Map<String, Object>) ((ConcurrentHashMap<String, Object>) this.types)
                .computeIfAbsent(definedIn.getName(), key -> new ConcurrentHashMap<>());
        return store.put(member, value);
    }
}
