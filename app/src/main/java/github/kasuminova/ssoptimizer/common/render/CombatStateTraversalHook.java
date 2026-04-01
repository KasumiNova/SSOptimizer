package github.kasuminova.ssoptimizer.common.render;

import org.lwjgl.opengl.GL11;

/**
 * 战斗状态遍历钩子。
 * <p>
 * 提供 {@code glFinish()} 调用控制，仅在调试模式下强制 GPU 同步，避免正常游戏时的性能損失。
 * 由 ASM 在战斗渲染循环中注入调用。
 */
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
