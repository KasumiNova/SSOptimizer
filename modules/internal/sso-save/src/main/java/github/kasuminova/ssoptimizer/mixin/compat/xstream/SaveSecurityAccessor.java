package github.kasuminova.ssoptimizer.mixin.compat.xstream;

import com.thoughtworks.xstream.security.TypePermission;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(targets = "com.thoughtworks.xstream.mapper.SecurityMapper", remap = false)
public interface SaveSecurityAccessor {
    @Accessor("permissions")
    List<TypePermission> ssoptimizer$getPermissions();
}
