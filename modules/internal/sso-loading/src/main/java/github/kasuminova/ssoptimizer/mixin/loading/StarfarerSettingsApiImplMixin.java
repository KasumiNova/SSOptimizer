package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.loading.ResourceLoaderThreadState;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = GameClassNames.STARFARER_SETTINGS_API_IMPL_DOTTED)
public abstract class StarfarerSettingsApiImplMixin {
    @Redirect(method = {"loadCSV(Ljava/lang/String;Z)Lorg/json/JSONArray;",
            "loadJSON(Ljava/lang/String;Z)Lorg/json/JSONObject;"}, remap = false,
            at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC,
                    target = "Lcom/fs/util/ResourceLoader;suppressCustomResources:Z"))
    private static void ssoptimizer$setSuppressCustomResources(final boolean suppress) {
        ResourceLoaderThreadState.setSuppressCustomResources(suppress);
    }
}
