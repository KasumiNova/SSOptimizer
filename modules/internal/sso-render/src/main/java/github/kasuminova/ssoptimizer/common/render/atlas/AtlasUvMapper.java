package github.kasuminova.ssoptimizer.common.render.atlas;

public final class AtlasUvMapper {

    private AtlasUvMapper() {
    }

    public record RemappedUv(float texX, float texY, float texWidth, float texHeight,
                             float insetU, float insetV,
                             float originU, float originV, float scaleU, float scaleV) {
    }

    public static RemappedUv remapFromOrigin(
            final float originX, final float originY,
            final float originWidth, final float originHeight,
            final float srcW, final float srcH,
            final int regionX, final int regionY, final int atlasSize) {
        final float scaleU = srcW / atlasSize;
        final float scaleV = srcH / atlasSize;
        final float originU = (float) regionX / atlasSize;
        final float originV = (float) regionY / atlasSize;
        return new RemappedUv(
                originU + originX * scaleU,
                originV + originY * scaleV,
                originWidth * scaleU,
                originHeight * scaleV,
                0.001F * scaleU,
                0.001F * scaleV,
                originU, originV, scaleU, scaleV);
    }

    public static float originalToAtlas(final float origin, final float scale, final float original) {
        return origin + original * scale;
    }

    public static float atlasToOriginal(final float origin, final float scale, final float atlas) {
        return (atlas - origin) / scale;
    }
}
