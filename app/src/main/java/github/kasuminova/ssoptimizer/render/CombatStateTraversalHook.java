package github.kasuminova.ssoptimizer.render;

import org.lwjgl.opengl.GL11;

public final class CombatStateTraversalHook {
    private CombatStateTraversalHook() {
    }

    public static boolean shouldCallFinish(boolean debugMode) {
        return debugMode;
    }

    public static void callFinishIfEnabled() {
        if (shouldCallFinish(Boolean.getBoolean("ssoptimizer.render.allowFinish"))) {
            GL11.glFinish();
        }
    }
}
