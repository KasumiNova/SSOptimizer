package github.kasuminova.ssoptimizer.common.render.spritebatch;

import com.fs.graphics.util.GLListManager;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sprite 合批 P0 统计的静态门面（GL 胶水层）。
 * <p>
 * 由 {@code SpriteMixin} 在 render/renderNoBind 内调用 {@link #onSpriteRender}，
 * 由 {@code CombatEngineScopeMixin} 在 {@code CombatEngine.render(Z)} 头尾维护
 * 战斗作用域。开关：{@code -Dssoptimizer.render.spritebatch.stats=true}（默认 false，
 * 关闭时 onSpriteRender 仅一次布尔判断）。每 300 个战斗帧输出一次汇总。
 * <p>
 * P0 阶段只统计不改绘制；统计逻辑全部在 {@link SpriteGroupStats}（可单测），
 * 本类只做 GL 状态采样（禁区判定）。
 */
public final class SpriteBatchStats {
    private static final Logger LOGGER = Logger.getLogger(SpriteBatchStats.class);

    public static final String STATS_PROPERTY = "ssoptimizer.render.spritebatch.stats";
    private static final int REPORT_INTERVAL_FRAMES = 300;

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty(STATS_PROPERTY, "false"));
    /** 分离模式缓存（进程生命周期内不变，避免每 sprite 一次系统属性查询）。 */
    private static final boolean RT_MODE = RenderThreadMode.isEnabled();
    private static final SpriteGroupStats STATS = new SpriteGroupStats();
    /** 分离模式下统计停用的一次性告警标记。 */
    private static final AtomicBoolean RT_DISABLED_WARNED = new AtomicBoolean();

    /** 战斗渲染作用域标记（仅渲染线程读写）。 */
    private static boolean combatScope;

    private SpriteBatchStats() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    /** @return 当前是否处于战斗渲染作用域（渲染线程独占标记）。 */
    public static boolean isInCombatScope() {
        return combatScope;
    }

    /** 进入 CombatEngine.render。 */
    public static void beginCombatScope() {
        combatScope = true;
    }

    /** 离开 CombatEngine.render：折叠一帧并按周期间隔输出汇总。 */
    public static void endCombatScope() {
        combatScope = false;
        if (!ENABLED) {
            return;
        }
        STATS.endFrame();
        if (STATS.frames() % REPORT_INTERVAL_FRAMES == 0) {
            LOGGER.info(STATS.report());
        }
    }

    /**
     * 记录一次外部 flush barrier（{@code SpriteBatch.flushPending()} 被非 sprite 绘制边界 /
     * 拒绝路径触发时调用）：统计上关闭当前段。仅统计开启时有实际开销。
     */
    public static void onFlushBarrier() {
        if (ENABLED && combatScope) {
            STATS.barrier();
        }
    }

    /**
     * 记录一次 sprite 绘制（render/renderNoBind 共用入口）。
     *
     * @param textureId 纹理 ID
     * @param blendSrc  混合源因子（GL 枚举）
     * @param blendDest 混合目标因子（GL 枚举）
     * @param noBind    是否 renderNoBind 路径
     */
    public static void onSpriteRender(int textureId, int blendSrc, int blendDest, boolean noBind) {
        if (!ENABLED || !combatScope) {
            return;
        }
        if (RT_MODE) {
            // 分离模式下主线程无 GL 上下文：禁区判定的两次 glGetBoolean 会退化为
            // 每 sprite 两次阻塞式队列往返（实测战斗场景可拖到 0.1 FPS），且采到的
            // 是渲染线程回放态而非录制态，统计语义已失真——整体停用并一次性告警
            if (RT_DISABLED_WARNED.compareAndSet(false, true)) {
                LOGGER.warn("[SSOptimizer] 渲染线程分离模式下 SpriteBatch 统计的 GL 状态采样不可用，"
                        + "onSpriteRender 统计已停用（如需统计请关闭 " + RenderThreadMode.ENABLE_PROPERTY + "）");
            }
            return;
        }
        // 禁区判定：display list 编译区间 / stencil / scissor 开启时不参与合批统计
        boolean forbidden = GLListManager.buildingList
                || GL11.glGetBoolean(GL11.GL_STENCIL_TEST)
                || GL11.glGetBoolean(GL11.GL_SCISSOR_TEST);
        STATS.record(SpriteGroupStats.key(textureId, blendSrc, blendDest), noBind, forbidden);
    }
}
