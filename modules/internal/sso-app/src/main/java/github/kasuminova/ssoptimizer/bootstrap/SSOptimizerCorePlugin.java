package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import io.github.nanoforged.api.CoreModContext;
import io.github.nanoforged.api.INanoCorePlugin;
import io.github.nanoforged.api.SystemAsmBridge;

import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

public final class SSOptimizerCorePlugin implements INanoCorePlugin {
    @Override
    public void onLoad(CoreModContext context) {
        SharedBootstrap.install(SSOptimizerCorePlugin::registerSystem);
    }

    static void registerEngineProcessors(BiConsumer<String, AsmClassProcessor> registrator) {
        SharedBootstrap.registerEngineProcessors(registrator, SSOptimizerCorePlugin::registerSystem);
    }

    private static void registerSystem(String name, UnaryOperator<byte[]> processor) {
        SystemAsmBridge.register(name, processor::apply);
    }
}
