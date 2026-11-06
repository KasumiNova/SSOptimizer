package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.starfarer.combat.Bounds;
import github.kasuminova.ssoptimizer.common.render.tessellation.ShipMaskMeshCache;
import github.kasuminova.ssoptimizer.common.render.tessellation.ShipMaskTessellationToggle;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(targets = GameClassNames.TESSELATOR_DOTTED)
public abstract class TesselatorMixin {

    @Overwrite(remap = false)
    public static void renderTriangles(Bounds bounds, float r, float g, float b) {
        if (!ShipMaskTessellationToggle.isEnabled()) {
            ShipMaskMeshCache.renderWithGlu(bounds, r, g, b);
            return;
        }
        ShipMaskMeshCache.render(bounds, r, g, b);
    }
}
