package github.kasuminova.ssoptimizer.common.render.hud;

import com.fs.graphics.Sprite;
import com.fs.graphics.TextureObject;

public interface RadarCompositeCache {
    static RadarCompositeCache getInstance() {
        return RadarCompositeCacheImpl.getInstance();
    }

    boolean isAvailable();

    int acquireCell(Object owner);

    boolean touchCell(int cell, Object owner);

    void bakeCell(int cell, Sprite portrait, boolean withSpotlight, float gridShiftY);

    int cellContentUv(int cell, float[] out);

    TextureObject compositeTexture();
}
