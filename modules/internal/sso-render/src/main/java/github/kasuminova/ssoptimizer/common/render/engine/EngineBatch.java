package github.kasuminova.ssoptimizer.common.render.engine;

public interface EngineBatch {
    static EngineBatch getInstance() {
        return EngineBatchImpl.getInstance();
    }

    /** @param engine 必须由 Mixin 注入 {@link EngineBridge}。 */
    void render(Object engine, float alphaScale);
}
