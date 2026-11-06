package github.kasuminova.ssoptimizer.common.render;

import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.lwjgl.opengl.GL11;

public final class CombatStateTraversalHook {
    private CombatStateTraversalHook() {
    }

    public static boolean shouldCallFinish(boolean debugMode) {
        return debugMode;
    }

    public static void callFinishIfEnabled() {
        if (!shouldCallFinish(Boolean.getBoolean("ssoptimizer.render.allowFinish"))) {
            return;
        }
        if (RenderThreadMode.isEnabled()) {
            github.kasuminova.ssoptimizer.bridge.opengl.GL11.glFinish();
        } else {
            GL11.glFinish();
        }
    }
}
