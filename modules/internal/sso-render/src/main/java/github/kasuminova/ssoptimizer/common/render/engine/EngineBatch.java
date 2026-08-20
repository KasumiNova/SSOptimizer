package github.kasuminova.ssoptimizer.common.render.engine;

/**
 * 引擎渲染合批入口。
 * <p>
 * 动机：原版 {@code Engine.render(float)} 每引擎槽约 9 次 glBegin/glEnd 与大量矩阵栈操作，
 * 30 舰 4 槽规模下每帧 1000+ drawcall。本接口以「每舰收集 + 立即 flush」策略合批：
 * {@code Engine.render} 被调时对本舰所有槽收集实例并按 阶段×纹理ID 分组，
 * 每舰最多 4 个 drawcall（additive 混合无深度测试，舰内跨槽重排严格等价）。
 * <p>
 * 实现：{@link EngineBatchImpl}（单例，GL 资源在渲染线程首次调用时惰性初始化）。
 */
public interface EngineBatch {
    /**
     * 获取全局单例。
     *
     * @return 合批渲染器实例
     */
    static EngineBatch getInstance() {
        return EngineBatchImpl.getInstance();
    }

    /**
     * 渲染一个引擎实体的全部喷口（Ship/Missile 的 Engine.render 替换路径）。
     *
     * @param engine     引擎实体（须实现 {@link EngineBridge}，即 EngineRenderMixin 注入目标）
     * @param alphaScale 全局 alpha 缩放（原版 render(float) 入参）
     */
    void render(Object engine, float alphaScale);
}
