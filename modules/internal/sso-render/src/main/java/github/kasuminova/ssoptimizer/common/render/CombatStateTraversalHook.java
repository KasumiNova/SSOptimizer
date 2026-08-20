package github.kasuminova.ssoptimizer.common.render;

import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.lwjgl.opengl.GL11;

/**
 * 战斗状态遍历钩子。
 * <p>
 * 提供 {@code glFinish()} 调用控制，仅在调试模式下强制 GPU 同步，避免正常游戏时的性能損失。
 * 由 ASM 在战斗渲染循环中注入调用。
 * <p>
 * 渲染线程分离模式下改走 bridge GL11.glFinish（入队命令，帧同步语义由
 * swapFramesAndSync 统一保证）；非分离模式保持直接调用。即便不显式分流，
 * 分离模式下本类的 org.lwjgl 调用也会被 RenderThreadRedirectTransformer 改写
 * 到同一终点——此处显式分流是为语义可读与单测可断言。
 */
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
