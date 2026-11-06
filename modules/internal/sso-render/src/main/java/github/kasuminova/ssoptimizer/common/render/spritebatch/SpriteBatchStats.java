package github.kasuminova.ssoptimizer.common.render.spritebatch;

import com.fs.graphics.util.GLListManager;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;

public final class SpriteBatchStats {
    private static final Logger LOGGER = Logger.getLogger(SpriteBatchStats.class);

    public static final String STATS_PROPERTY = "ssoptimizer.render.spritebatch.stats";
    private static final int REPORT_INTERVAL_FRAMES = 300;

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty(STATS_PROPERTY, "false"));
    private static final SpriteGroupStats STATS = new SpriteGroupStats();

    private static boolean combatScope;

    private SpriteBatchStats() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static boolean isInCombatScope() {
        return combatScope;
    }

    public static void beginCombatScope() {
        combatScope = true;
    }

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

    public static void onFlushBarrier() {
        if (ENABLED && combatScope) {
            STATS.barrier();
        }
    }

    public static void onSpriteRender(int textureId, int blendSrc, int blendDest, boolean noBind) {
        if (!ENABLED || !combatScope) {
            return;
        }
        boolean forbidden = GLListManager.buildingList
                || GL11.glGetBoolean(GL11.GL_STENCIL_TEST)
                || GL11.glGetBoolean(GL11.GL_SCISSOR_TEST);
        STATS.record(SpriteGroupStats.key(textureId, blendSrc, blendDest), noBind, forbidden);
    }
}
