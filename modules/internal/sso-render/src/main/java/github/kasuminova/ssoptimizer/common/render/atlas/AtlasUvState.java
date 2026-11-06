package github.kasuminova.ssoptimizer.common.render.atlas;

public interface AtlasUvState {
    boolean ssoptimizer$isAtlasRemapped();

    float ssoptimizer$atlasInsetU();

    float ssoptimizer$atlasInsetV();

    boolean ssoptimizer$ensureAtlasRemapped(String renderMethod);
}
