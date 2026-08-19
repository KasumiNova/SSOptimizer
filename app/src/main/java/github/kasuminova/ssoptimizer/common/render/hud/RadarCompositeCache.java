package github.kasuminova.ssoptimizer.common.render.hud;

import com.fs.graphics.Sprite;
import com.fs.graphics.TextureObject;

/**
 * 雷达条图标肖像合成缓存（FBO 离屏一次合成，每帧一次普通合批绘制）。
 * <p>
 * 动机：原版 {@code ShipPortraitRenderer.render} 每次调用开启 stencil+alpha test，
 * 内嵌 3-4 次 sprite 绘制（肖像/聚光灯/网格），全程约 40 次 GL 状态调用与 stencil
 * 缓冲读写，且 stencil 区间使 SpriteBatch 拒绝合批；P3 基准中
 * {@code RadarRibbonIconManager.renderAll} 占 4.8%，其中肖像合成是大头。
 * 合成内容（剪影 alpha、网格、聚光灯）对同一图标完全静态，每帧唯一变化是透明度，
 * 因此把「网格+聚光灯×剪影」合成结果一次性烘焙进共享 FBO 纹理的单元格，
 * 每帧改为一次 additive sprite 绘制（全图标共享同一纹理与 blend，可跨图标合批）。
 * <p>
 * 单元格生命周期：图标首次渲染时 {@link #acquireCell} 分配并烘焙；
 * 每帧绘制前 {@link #touchCell} 续期；超过 {@code STALE_NANOS} 未触碰的单元格在
 * 下次分配时回收（图标随舰船死亡/战斗结束消失后自动释放）。
 * <p>
 * 全部方法必须在渲染线程调用。实现：{@link RadarCompositeCacheImpl}。
 */
public interface RadarCompositeCache {
    /**
     * 获取全局单例。
     *
     * @return 合成缓存实例
     */
    static RadarCompositeCache getInstance() {
        return RadarCompositeCacheImpl.getInstance();
    }

    /**
     * 缓存是否可用（FBO 创建成功且纹理已分配）。不可用时调用方回退原版渲染。
     *
     * @return true 表示可分配/烘焙
     */
    boolean isAvailable();

    /**
     * 为图标分配一个合成单元格。
     *
     * @param owner 图标实例（用于触碰校验，弱引用持有）
     * @return 单元格编号；-1 表示单元格耗尽（调用方回退原版渲染）
     */
    int acquireCell(Object owner);

    /**
     * 触碰单元格续期。
     *
     * @param cell  单元格编号
     * @param owner 图标实例
     * @return false 表示单元格已被回收或易主，调用方应重新 acquire + bake
     */
    boolean touchCell(int cell, Object owner);

    /**
     * 在指定单元格内烘焙合成图（{@code withSpotlight=false} 为幽灵位变体）。
     * 复刻原版序列：alpha-only 阶段累积 肖像α×聚光灯α 剪影，随后 additive 阶段
     * 叠加 聚光灯（仅 withSpotlight）与网格（均按 DST_ALPHA 加权）。
     * 调用前必须先把 portrait 的颜色/尺寸设置到位（颜色 alpha 参与剪影累积）。
     *
     * @param cell       单元格编号
     * @param portrait   图标肖像 sprite（尺寸 = 图标当前尺寸）
     * @param withSpotlight 是否叠加成员聚光灯层（真实成员 true，幽灵位 false）
     * @param gridShiftY 网格 Y 对齐偏移（原版 {@code (int)g - g} 的亚像素对齐）
     */
    void bakeCell(int cell, Sprite portrait, boolean withSpotlight, float gridShiftY);

    /**
     * 读取单元格内容区的 UV 矩形（内容在格内居中裁剪区，大小为最后一次烘焙的
     * 内衬矩形），填充 {@code out[4] = texX, texY, texWidth, texHeight}。
     *
     * @param cell 单元格编号
     * @param out  输出数组（长度 ≥4）
     * @return 内容区像素宽（int 部分，供 sprite setSize 使用），打包为高 16 位宽、低 16 位高
     */
    int cellContentUv(int cell, float[] out);

    /**
     * 合成共享纹理（全部单元格所在的大纹理）。
     *
     * @return 合成纹理；缓存不可用时为 null
     */
    TextureObject compositeTexture();
}
