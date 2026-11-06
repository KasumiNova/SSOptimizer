package github.kasuminova.ssoptimizer.mixin.modopt.dcr;

import github.kasuminova.ssoptimizer.modopt.dcr.DcrCompressionHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(targets = "data.scripts.combatanalytics.util.CompressionUtil")
public abstract class DcrCompressionUtilMixin {

    @Overwrite(remap = false)
    public static byte[] compress(final String text) {
        return DcrCompressionHelper.compress(text);
    }

    @Overwrite(remap = false)
    public static String decompress(final byte[] bytes) {
        return DcrCompressionHelper.decompress(bytes);
    }
}
