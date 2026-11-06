package github.kasuminova.ssoptimizer.common.debug;

import github.kasuminova.ssoptimizer.api.debug.DebugScript;

public record ScriptHandle(String scriptId,
                           String className,
                           int version,
                           DebugScript instance,
                           ClassLoader classLoader,
                           long compiledAt) {
}
