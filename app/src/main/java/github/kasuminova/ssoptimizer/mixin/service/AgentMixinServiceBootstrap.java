package github.kasuminova.ssoptimizer.mixin.service;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

/**
 * ServiceLoader entry point that tells Mixin which {@link org.spongepowered.asm.service.IMixinService}
 * implementation to use in a Java-agent environment.
 */
public final class AgentMixinServiceBootstrap implements IMixinServiceBootstrap {

    @Override
    public String getName() {
        return "SSOptimizer/Agent";
    }

    @Override
    public String getServiceClassName() {
        return "github.kasuminova.ssoptimizer.mixin.service.AgentMixinService";
    }

    @Override
    public void bootstrap() {
        // No extra bootstrap steps needed — the agent premain already
        // installed Instrumentation and classloader visibility.
    }
}
