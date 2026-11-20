package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.particle.SmoothParticle;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.awt.Color;

@Mixin(targets = GameClassNames.SMOKE_PARTICLE_DOTTED)
public abstract class SmokeParticleMixin extends SmoothParticle {
    protected SmokeParticleMixin(Color color, float size) {
        super(color, size);
    }

    @Overwrite(remap = false)
    public void preBatch() {
        super.preBatch();
    }
}
