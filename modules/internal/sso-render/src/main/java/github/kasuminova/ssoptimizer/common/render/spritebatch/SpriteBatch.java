package github.kasuminova.ssoptimizer.common.render.spritebatch;

public interface SpriteBatch {
    static SpriteBatch getInstance() {
        return SpriteBatchImpl.getInstance();
    }

    boolean submitIfActive(int textureId,
                           float posX, float posY, float width, float height,
                           float centerX, float centerY, float angle,
                           int r, int g, int b, int a,
                           int blendSrc, int blendDest,
                           float texX, float texY, float texWidth, float texHeight,
                           boolean texClamp);

    void flushPending();
}
