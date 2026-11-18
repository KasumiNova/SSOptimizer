package ssoptimizer.standalone.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import ssoptimizer.standalone.game.SelectedMod;

@Mixin(targets = "com.fs.starfarer.launcher.ModManager$ModSpec", remap = false)
public abstract class ModSpecMixin implements SelectedMod {
    @Override @Shadow public abstract String getId();
}
