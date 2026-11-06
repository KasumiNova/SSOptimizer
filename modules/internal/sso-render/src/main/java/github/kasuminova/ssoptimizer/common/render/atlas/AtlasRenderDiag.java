package github.kasuminova.ssoptimizer.common.render.atlas;

import org.apache.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AtlasRenderDiag {

    private static final Logger LOGGER = Logger.getLogger(AtlasRenderDiag.class);

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private AtlasRenderDiag() {
    }

    public static void warnUnremappedSample(final String texturePath, final String renderMethod,
                                            final float texX, final float texY,
                                            final float texWidth, final float texHeight) {
        if (!LOGGED.add(texturePath + '|' + renderMethod)) {
            return;
        }
        LOGGER.warn("[SSOptimizer][AtlasDiag] 未重映射精灵采样图集纹理（将惰性重映射治愈）：path="
                + texturePath + " render=" + renderMethod
                + " uv=(" + texX + "," + texY + "," + texWidth + "," + texHeight + ")",
                new Throwable("AtlasDiag 调用栈溯源"));
    }

    public static int loggedCount() {
        return LOGGED.size();
    }

    public static void resetForTest() {
        LOGGED.clear();
    }
}
