package github.kasuminova.ssoptimizer.common.render.spritebatch;

import com.fs.graphics.util.GLListManager;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;

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
    private static final SpriteGroupStats STATS = new SpriteGroupStats();

    /** 战斗渲染作用域标记（仅渲染线程读写）。 */
    private static boolean combatScope;

    private SpriteBatchStats() {
    }

    public static boolean isEnabled() {
        return ENABLED;
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
        // 禁区判定：display list 编译区间 / stencil / scissor 开启时不参与合批统计
        boolean forbidden = GLListManager.buildingList
                || GL11.glGetBoolean(GL11.GL_STENCIL_TEST)
                || GL11.glGetBoolean(GL11.GL_SCISSOR_TEST);
        STATS.record(SpriteGroupStats.key(textureId, blendSrc, blendDest), noBind, forbidden);
    }
}
