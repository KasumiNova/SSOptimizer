package github.kasuminova.ssoptimizer.common.render.spritebatch;

/**
 * Sprite 合批渲染入口（P1：流式严格保序）。
 * <p>
 * 动机：原版 {@code Sprite.render} 每 sprite 一次纹理绑定 + 矩阵栈操作 + 一组
 * 立即模式 quad（P0 实测战斗帧均值 2238 次绘制、严格保序 run 1002 个）。
 * 本接口把「战斗作用域内连续同组（纹理×blend）的 sprite 绘制」合并为单次
 * VBO drawcall：收集时读取 MVP 矩阵把顶点烘焙到裁剪空间，
 * 组切换 / 禁区 / 已知非 sprite 绘制边界 / 作用域结束时立即 flush，
 * 绘制相对顺序与原版逐位一致。
 * <p>
 * 实现：{@link SpriteBatchImpl}（单例，GL 资源渲染线程惰性初始化）。
 * 顶点打包逻辑见 {@link SpriteQuadPacker}（纯逻辑，可单测）。
 */
public interface SpriteBatch {
    /**
     * 获取全局单例。
     *
     * @return 合批渲染器实例
     */
    static SpriteBatch getInstance() {
        return SpriteBatchImpl.getInstance();
    }

    /**
     * 尝试把一次 sprite 绘制纳入合批。
     * <p>
     * 命中以下任一情况时不收集、返回 false（调用方走原版绘制路径）；若当前有
     * 未 flush 的批次会先 flush 以保持绘制顺序：
     * 合批总开关关闭 / 非战斗作用域 / texClamp / display list 编译区间 /
     * stencil 或 scissor 开启 / 当前矩阵模式非 MODELVIEW。
     *
     * @param textureId 纹理 ID
     * @param posX      sprite 左下角 X（已含 offsetX）
     * @param posY      sprite 左下角 Y（已含 offsetY）
     * @param width     sprite 宽
     * @param height    sprite 高
     * @param centerX   旋转枢轴 X（-1 表示取 width/2）
     * @param centerY   旋转枢轴 Y（-1 表示取 height/2）
     * @param angle     旋转角（度）
     * @param r         顶点色 R（0..255）
     * @param g         顶点色 G
     * @param b         顶点色 B
     * @param a         顶点色 A（color.getAlpha() * alphaMult 的 int 截断值）
     * @param blendSrc  混合源因子（GL 枚举）
     * @param blendDest 混合目标因子（GL 枚举）
     * @param texX      UV 起点 U
     * @param texY      UV 起点 V
     * @param texWidth  UV 宽
     * @param texHeight UV 高
     * @param texClamp  是否要求纹理 clamp（clamp sprite 不参与合批）
     * @return true 表示已收集（调用方直接返回，不再绘制）
     */
    boolean submitIfActive(int textureId,
                           float posX, float posY, float width, float height,
                           float centerX, float centerY, float angle,
                           int r, int g, int b, int a,
                           int blendSrc, int blendDest,
                           float texX, float texY, float texWidth, float texHeight,
                           boolean texClamp);

    /**
     * 立即绘制当前累积的批次（无累积时零开销返回）。
     * 在非 sprite 绘制边界（引擎/护盾/decal/stencil 掩码/模组插件渲染入口）
     * 与战斗作用域结束时必须调用，保证绘制相对顺序与原版一致。
     */
    void flushPending();
}
