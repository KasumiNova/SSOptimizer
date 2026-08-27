package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.graphics.Sprite;
import com.fs.graphics.TextureObject;
import com.fs.graphics.util.Fader;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.campaign.fleet.CampaignFleetMemberView;
import com.fs.starfarer.prototype.Utils;
import github.kasuminova.ssoptimizer.mixin.accessor.EngineGlowSlotAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.SpriteUvAccessor;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * 战役舰队引擎辉光（{@code CampaignShipEngineGlow.render}）的合批渲染：把原版
 * 逐槽 2×Vector2f 分配 + 逐槽 8 顶点 immediate 发射折叠为整船单次
 * {@code glDrawArrays(GL_QUADS)}，范式与战役尾迹
 * {@link CampaignContrailBatchHelper} 一致（静态直接缓冲 + client array）。
 * <p>
 * 与原版 {@code CampaignShipEngineGlow.render} 的语义对照（反编译 named 仓
 * {@code CampaignShipEngineGlow.java:237-305} 逐行核对）：
 * <ul>
 *   <li><b>原版已是单 begin/end</b>：{@code glBegin(GL_QUADS)} 在槽循环外
 *       （:252/:294），每槽 2 个 quad 共 8 顶点。收益点不在合并 begin/end，
 *       而在消灭逐槽 3 个 {@code new Vector2f}（{@code Utils.rotate} 内 1 个 +
 *       调用点 2 个）与 8×3 次逐顶点开销；</li>
 *   <li><b>顶点数学逐表达式照搬</b>：方向向量沿用 {@code Utils.rotate((0,1),
 *       angle-90)} 的原始表达式（{@code 0.0f*cos - 1.0f*sin}，位级保留 ±0.0
 *       语义），长度/宽度公式保持原版运算顺序与结合性，每船恒量
 *       （widthMult 钳制、颜色字节）提升出循环不改变结果；</li>
 *   <li><b>顶点/UV/颜色序列不变</b>：quad1 = 船内侧（alpha 0）→槽位（alpha 全量），
 *       quad2 = 槽位（alpha 全量）→引擎朝向延伸端（alpha 0），tex 角点与原版
 *       逐顶点序列一致；</li>
 *   <li><b>槽几何缓存</b>：槽位 angle/offset/width/baseLength/glowSize 为构造期
 *       定值，8 顶点坐标是 scale 元组（widthMult, lengthScale, engineHeightMult,
 *       fullBrightness）的纯函数——按元组的 float 位级键（Float.floatToRawIntBits）
 *       缓存顶点坐标（存储于 {@code CampaignShipEngineGlowMixin} 的 @Unique 字段
 *       {@link GlowGeometryCache}），命中时只重写颜色字节；cos/sin 方向向量
 *       （构造期定值）同样只算一次。稳态巡航下 Fader IDLE/ValueShifter 基准值使
 *       元组恒定，过渡期逐帧 miss 回退全编码，两条路径输出位级一致；</li>
 *   <li><b>矩阵命令保持一次</b>：pushMatrix/rotate/popMatrix 是每船固有开销
 *       （调用方平移 + 本方法旋转），不合并、不省略；</li>
 *   <li><b>current color 恢复</b>：原版 glEnd 后的 current color 恒为最后一次
 *       {@code setGlColor(color, 0)}；client array 绘制不改 current color，
 *       绘制后显式 {@code glColor4ub} 恢复同一值；</li>
 *   <li><b>hitGlow 同船合批</b>：原版逐槽 {@code setColor}（同一
 *       {@code engineGlowColor.getCurr()}）合并为循环外一次；逐槽 {@code setSize}
 *       状态写回保留（原版状态语义），渲染侧把全部槽的 hitGlow quad 编进静态缓冲
 *       单次 draw——hitGlow 精灵 angle 恒 0、无自设 center/offset，几何为轴对齐
 *       quad {@code [off±size/2]}（原版旋转来自外层矩阵），同纹理同 blend
 *       （SRC_ALPHA, ONE，与外层状态一致）。UV 读精灵真实 texX/texY/texWidth/
 *       texHeight（{@code SpriteAtlasMixin} 重映射后为图集 GL 空间，非 0..1），
 *       经 {@link github.kasuminova.ssoptimizer.mixin.accessor.SpriteUvAccessor}
 *       注入读取。原版逐精灵 render 的 blend enable/disable 与 current color 写回
 *       在合批后保持最终状态一致（blend 关闭、current color = hitGlow 颜色）。
 *       hitGlow 从不调用 setTexClamp（反编译 {@code CampaignShipEngineGlow}
 *       逐行核对），合批路径不处理 clamp；</li>
 *   <li><b>hitGlow 视口 LOD</b>：舰队位置超出视口
 *       {@link #HIT_GLOW_LOD_MARGIN} 边距时整船跳过 hitGlow 精灵（尺寸数十
 *       单位量级，该边距下不可见）。编队展示等 UI 场景的 dummy fleet
 *       （{@code isInCurrentLocation() == false}）不应用 LOD，保持原版渲染；</li>
 *   <li><b>RT 兼容</b>：只发出 client array + draw + 状态指令，无 glGet* 回读。</li>
 * </ul>
 * <b>不做跨船合批的理由</b>：{@code CampaignFleetMemberView.renderSingle} 在引擎
 * 辉光前后穿插船体 sprite、武器、模块与 jitter 渲染，且每船矩阵为「平移 +
 * 旋转」组合；跨船单批次需要把辉光延迟到整队渲染末尾（改变与 jitter/后续船
 * 绘制的交错顺序）或把逐船矩阵烘焙进顶点（GL 矩阵为 double 累积，float 预
 * 烘焙存在精度差）。两者都动渲染顺序/精度语义，风险大于收益，放弃。
 */
public final class CampaignEngineGlowRenderHelper {
    /**
     * hitGlow 视口距离 LOD 外扩边距（{@code -Dssoptimizer.render.engineglow.lod.margin}，
     * 默认 3000）。舰队位置超出可视区域该边距时跳过整船 hitGlow 精灵；默认值远大
     * 于 hitGlow 实际尺寸（glowSize 数十单位），屏幕边缘不会出现可见缺失。
     * 设为 {@code Float.MAX_VALUE} 等效关闭 LOD（任何位置都判近视口）。
     */
    public static final float HIT_GLOW_LOD_MARGIN =
            Float.parseFloat(System.getProperty("ssoptimizer.render.engineglow.lod.margin", "3000"));

    /** 批次上限：8192 顶点 = 1024 个引擎槽，正常舰船（1~6 槽）永不触发中途 flush。 */
    private static final int MAX_VERTICES = 8192;
    /** 原版 var13：船内侧反向延伸量（单位）。 */
    private static final float HEAD_BACKOFF = 3.0f;

    private static ByteBuffer  colorBuf;
    private static FloatBuffer vertexBuf;
    private static FloatBuffer texCoordBuf;
    private static int         numVertices;

    private CampaignEngineGlowRenderHelper() {
    }

    /** 原版 render 的两组帧内缩放：sizeScale 作用于 hitGlow 尺寸，lengthScale 作用于辉光条长度。 */
    public record GlowScales(float sizeScale, float lengthScale) {
    }

    /**
     * 原版 render 方法头的缩放计算（var5/var6），运算顺序与结合性逐行照搬。
     *
     * @param fullBrightness  fullFader 当前亮度（原版 var4）
     * @param accelBrightness accelFader 当前亮度
     */
    public static GlowScales computeScales(final float fullBrightness, final float accelBrightness) {
        float sizeScale = 1.0f + 0.75f * (1.0f - accelBrightness);
        float lengthScale = 1.0f + (accelBrightness - 0.75f);
        sizeScale *= 0.5f + 0.5f * fullBrightness;
        lengthScale *= 0.5f + 0.5f * fullBrightness;
        return new GlowScales(sizeScale, lengthScale);
    }

    /**
     * hitGlow 距离 LOD 判定。
     *
     * @param fleetInCurrentLocation 舰队是否位于当前位置（编队展示等 UI 的 dummy
     *                               fleet 为 false，此时不应用 LOD，保持原版渲染）
     * @param fleetLocation          舰队当前位置
     * @param viewport               当前战役视口
     * @return 若整船 hitGlow 精灵应跳过渲染则返回 {@code true}
     */
    public static boolean shouldSkipHitGlow(final boolean fleetInCurrentLocation,
                                            final Vector2f fleetLocation,
                                            final ViewportAPI viewport) {
        if (!fleetInCurrentLocation) {
            return false;
        }
        return !viewport.isNearViewport(fleetLocation, HIT_GLOW_LOD_MARGIN);
    }

    /**
     * 整船引擎辉光渲染入口（{@code CampaignShipEngineGlowMixin} 覆写体的全部内容）。
     * 矩阵命令（push/rotate/pop）与状态命令（enable/blend/bind）保持原版顺序；
     * 辉光条 quad 与 hitGlow quad 各走 client array 单 draw（hitGlow 绑定顺序在
     * 辉光条之后，与原版一致）。
     *
     * @param view         舰队成员视图（shifter 数据源）
     * @param slots        原版 {@code slots}（元素为 SlotData，运行期已并入
     *                     {@link EngineGlowSlotAccessor}）
     * @param glowTexture  原版 {@code glow} 纹理
     * @param hitGlow      原版 {@code hitGlow} 精灵
     * @param accelFader   原版 {@code accelFader}
     * @param fullFader    原版 {@code fullFader}
     * @param facing       船体朝向角（原版 var2）
     * @param alphaMult    全局透明度倍率（原版 var3）
     * @param fleet        视图所属舰队（LOD 距离数据源；可为 {@code null}，此时不
     *                     应用 LOD，保持原版渲染）
     * @param viewport     当前战役视口（可为 {@code null}——非战役上下文无 sector，
     *                     此时不应用 LOD，保持原版渲染）
     */
    public static void render(final CampaignFleetMemberView view,
                              final List<?> slots,
                              final TextureObject glowTexture,
                              final Sprite hitGlow,
                              final Fader accelFader,
                              final Fader fullFader,
                              final float facing,
                              final float alphaMult,
                              final CampaignFleetAPI fleet,
                              final ViewportAPI viewport,
                              final GlowGeometryCache geometryCache) {
        final float fullBrightness = fullFader.getBrightness();
        final GlowScales scales = computeScales(fullBrightness, accelFader.getBrightness());
        hitGlow.setAlphaMult(alphaMult);

        GL11.glPushMatrix();
        GL11.glRotatef(facing, 0.0f, 0.0f, 1.0f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        glowTexture.bind();

        renderGlowQuads(slots,
                view.getEngineColor().getCurr(),
                view.getEngineWidthMult().getCurr(),
                view.getEngineHeightMult().getCurr(),
                fullBrightness, scales.lengthScale(), alphaMult,
                geometryCache);

        final boolean skipHitGlow = fleet == null || viewport == null
                ? false
                : shouldSkipHitGlow(fleet.isInCurrentLocation(), fleet.getLocation(), viewport);
        if (!skipHitGlow) {
            renderHitGlowSprites(hitGlow, slots,
                    view.getEngineGlowColor().getCurr(),
                    scales.sizeScale(),
                    view.getEngineGlowSizeMult().getCurr(),
                    alphaMult);
        }

        GL11.glPopMatrix();
    }

    /**
     * 辉光条 quad 批次：编码全部槽位后单次 {@code glDrawArrays(GL_QUADS)}。
     * 原版空槽时 begin/end 为空操作且无颜色副作用，此处整体跳过编码与绘制。
     */
    private static void renderGlowQuads(final List<?> slots,
                                        final Color color,
                                        final float engineWidthMult,
                                        final float engineHeightMult,
                                        final float fullBrightness,
                                        final float lengthScale,
                                        final float alphaMult,
                                        final GlowGeometryCache geometryCache) {
        if (slots.isEmpty()) {
            return;
        }

        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

            final byte r = (byte) color.getRed();
            final byte g = (byte) color.getGreen();
            final byte b = (byte) color.getBlue();
            final byte alphaZero = (byte) (color.getAlpha() * 0.0f);
            final byte alphaFull = (byte) (color.getAlpha() * alphaMult);

            encodeSlots(slots, geometryCache, engineWidthMult, engineHeightMult, fullBrightness,
                    lengthScale, r, g, b, alphaZero, alphaFull);
            flushBatch();

            // 原版 glEnd 后 current color 恒为最后一次 setGlColor(color, 0)
            GL11.glColor4ub(r, g, b, alphaZero);
        } finally {
            GL11.glPopClientAttrib();
        }
    }

    /**
     * 逐槽编码（无 GL 调用，可单测）：widthMult 钳制为每船恒量，提升出循环
     * （原版逐槽重算同一 {@code getCurr()}，结果不变）。槽几何经
     * {@link GlowGeometryCache} 按 scale 元组位级键缓存。
     */
    static void encodeSlots(final List<?> slots,
                            final GlowGeometryCache geometryCache,
                            final float engineWidthMult,
                            final float engineHeightMult,
                            final float fullBrightness,
                            final float lengthScale,
                            final byte r, final byte g, final byte b,
                            final byte alphaZero, final byte alphaFull) {
        beginBatch();
        float widthMult = engineWidthMult;
        if (widthMult > 1.0f) {
            widthMult = 1.0f + (widthMult - 1.0f) * fullBrightness;
        }
        int slotIndex = 0;
        for (final Object slot : slots) {
            encodeSlot((EngineGlowSlotAccessor) slot, slotIndex, geometryCache,
                    widthMult, lengthScale, engineHeightMult, fullBrightness,
                    r, g, b, alphaZero, alphaFull);
            slotIndex++;
        }
    }

    /**
     * 单槽 8 顶点编码：顶点数学逐表达式照搬原版（{@code Utils.rotate} 的
     * {@code 0.0f*cos - 1.0f*sin}、{@code scale(len)} 后加偏移、{@code scale(-3)}
     * 后加偏移），位级保留原版的浮点运算顺序。
     * <p>
     * 缓存键 = scale 元组（widthMult, lengthScale, engineHeightMult, fullBrightness）
     * 的 float 位级值：命中时直接发射缓存的 8 顶点坐标（写入的颜色字节为本次调用
     * 现值），与全编码路径位级一致；miss 时全编码并把算出的顶点坐标写回缓存。
     * 方向向量（angle 为构造期定值）只在首次 miss 时计算一次。
     */
    static void encodeSlot(final EngineGlowSlotAccessor slot,
                           final int slotIndex,
                           final GlowGeometryCache cache,
                           final float widthMult,
                           final float lengthScale,
                           final float engineHeightMult,
                           final float fullBrightness,
                           final byte r, final byte g, final byte b,
                           final byte alphaZero, final byte alphaFull) {
        if (numVertices + 8 > MAX_VERTICES) {
            // 超上限的极端槽数（正常舰船 1~6 槽）：中途 flush，视觉与原版一致
            // （同矩阵同批次语义的多次 draw）
            flushBatch();
        }

        final int keyWidthMult = Float.floatToRawIntBits(widthMult);
        final int keyLengthScale = Float.floatToRawIntBits(lengthScale);
        final int keyHeightMult = Float.floatToRawIntBits(engineHeightMult);
        final int keyFullBrightness = Float.floatToRawIntBits(fullBrightness);
        if (cache.isHit(slotIndex, keyWidthMult, keyLengthScale, keyHeightMult, keyFullBrightness)) {
            emitCachedSlot(cache, slotIndex, r, g, b, alphaZero, alphaFull);
            return;
        }

        final float dirX;
        final float dirY;
        if (cache.hasDirection(slotIndex)) {
            dirX = cache.directionX(slotIndex);
            dirY = cache.directionY(slotIndex);
        } else {
            final float radians = (slot.ssoptimizer$getAngle() - 90.0f) * Utils.degreesToRadians;
            final float cos = (float) Math.cos(radians);
            final float sin = (float) Math.sin(radians);
            // 原版 Utils.rotate((0,1), angle-90) 的展开表达式，位级保留 ±0.0 语义
            dirX = 0.0f * cos - 1.0f * sin;
            dirY = 0.0f * sin + 1.0f * cos;
            cache.storeDirection(slotIndex, dirX, dirY);
        }

        final float halfWidth = slot.ssoptimizer$getWidth() / 2.0f * widthMult;
        final float len = slot.ssoptimizer$getBaseLength() * lengthScale * engineHeightMult;
        final Vector2f offset = slot.ssoptimizer$getOffset();
        final float offX = offset.x;
        final float offY = offset.y;
        // 原版 var15：沿引擎朝向延伸 len 的延伸端
        final float tailX = dirX * len + offX;
        final float tailY = dirY * len + offY;
        // 原版 var16：船内侧反向 3 单位的内端
        final float headX = dirX * -HEAD_BACKOFF + offX;
        final float headY = dirY * -HEAD_BACKOFF + offY;
        final float halfHead = halfWidth / 2.0f;

        // 顶点坐标写回缓存后统一从缓存发射：miss 路径写出的字节序列与
        // 后续命中路径完全一致（同一份坐标值、同一组 UV/颜色参数）。
        cache.storeGeometry(slotIndex,
                keyWidthMult, keyLengthScale, keyHeightMult, keyFullBrightness,
                headX, headY + halfHead,
                headX, headY - halfHead,
                offX, offY - halfWidth,
                offX, offY + halfWidth,
                offX, offY - halfWidth,
                offX, offY + halfWidth,
                tailX, tailY + halfHead,
                tailX, tailY - halfHead);
        emitCachedSlot(cache, slotIndex, r, g, b, alphaZero, alphaFull);
    }

    /**
     * 从缓存发射单槽 8 顶点：UV 角点序列（1,1)(1,0)(0,0)(0,1）/（0,0)(0,1)(1,1)(1,0）
     * 与原版逐顶点一致；颜色字节为调用点现值（每船每帧重算，不入缓存键）。
     */
    private static void emitCachedSlot(final GlowGeometryCache cache, final int slotIndex,
                                       final byte r, final byte g, final byte b,
                                       final byte alphaZero, final byte alphaFull) {
        // quad1：内端（alpha 0）→槽位（alpha 全量）
        putVertex(cache.geometryAt(slotIndex, 0), cache.geometryAt(slotIndex, 1),
                1.0f, 1.0f, r, g, b, alphaZero);
        putVertex(cache.geometryAt(slotIndex, 2), cache.geometryAt(slotIndex, 3),
                1.0f, 0.0f, r, g, b, alphaZero);
        putVertex(cache.geometryAt(slotIndex, 4), cache.geometryAt(slotIndex, 5),
                0.0f, 0.0f, r, g, b, alphaFull);
        putVertex(cache.geometryAt(slotIndex, 6), cache.geometryAt(slotIndex, 7),
                0.0f, 1.0f, r, g, b, alphaFull);
        // quad2：槽位（alpha 全量）→延伸端（alpha 0）
        putVertex(cache.geometryAt(slotIndex, 8), cache.geometryAt(slotIndex, 9),
                0.0f, 0.0f, r, g, b, alphaFull);
        putVertex(cache.geometryAt(slotIndex, 10), cache.geometryAt(slotIndex, 11),
                0.0f, 1.0f, r, g, b, alphaFull);
        putVertex(cache.geometryAt(slotIndex, 12), cache.geometryAt(slotIndex, 13),
                1.0f, 1.0f, r, g, b, alphaZero);
        putVertex(cache.geometryAt(slotIndex, 14), cache.geometryAt(slotIndex, 15),
                1.0f, 0.0f, r, g, b, alphaZero);
    }

    /**
     * hitGlow 精灵同船合批渲染：原版逐槽 {@code setColor}（帧内恒量）合并为一次，
     * 逐槽 {@code setSize} 状态写回保留；渲染侧把全部槽的轴对齐 quad 编进静态缓冲，
     * bind hitGlow 纹理一次后单次 draw（原版每槽 2 次 JNI：streamBindTexture +
     * nativeRenderSprite）。空槽时原版不触 hitGlow 任何状态，此处整体跳过。
     * <p>
     * 与原版逐精灵 {@code render} 的状态对照：blendFunc 按精灵字段重设一次
     * （hitGlow 恒为 (SRC_ALPHA, ONE)，与外层状态相同）；绘制后 current color
     * 恢复为 hitGlow 颜色（alphaMult 缩放字节，原版最后一个精灵 render 后的
     * current color）；最终 GL_BLEND 关闭（原版逐精灵 render 末尾 disable）。
     * {@code texture == null} 时原版 render 整体 no-op：setColor/setSize 已发生，
     * 不编码不绘制。
     */
    static void renderHitGlowSprites(final Sprite hitGlow,
                                     final List<?> slots,
                                     final Color glowColor,
                                     final float sizeScale,
                                     final float glowSizeMult,
                                     final float alphaMult) {
        if (slots.isEmpty()) {
            return;
        }
        hitGlow.setColor(glowColor);

        final byte r = (byte) glowColor.getRed();
        final byte g = (byte) glowColor.getGreen();
        final byte b = (byte) glowColor.getBlue();
        // 原版 (byte)(color.getAlpha() * alphaMult)：float→byte 经 int 收窄，等价
        final byte a = (byte) (int) (glowColor.getAlpha() * alphaMult);
        final TextureObject texture = hitGlow.getTexture();
        if (texture == null) {
            encodeHitGlowQuads(hitGlow, slots, sizeScale, glowSizeMult, false, r, g, b, a);
            return;
        }

        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

            // bind/blendFunc 在编码前完成：编码内可能的中途 flush（极端槽数）
            // 与最终 flush 都以同一纹理/混合状态绘制，与原版逐精灵先 bind 后 draw
            // 的状态顺序一致
            texture.bind();
            GL11.glBlendFunc(hitGlow.getBlendSrc(), hitGlow.getBlendDest());
            encodeHitGlowQuads(hitGlow, slots, sizeScale, glowSizeMult, true, r, g, b, a);
            flushBatch();

            GL11.glColor4ub(r, g, b, a);
        } finally {
            GL11.glPopClientAttrib();
        }
        GL11.glDisable(GL11.GL_BLEND);
    }

    /**
     * hitGlow quad 逐槽编码（无 GL 调用，可单测）：逐槽 {@code setSize} 写回与原版
     * 状态语义一致；几何 = 原版 {@code renderAtCenter(x,y) → render(x-w/2, y-h/2)
     * → translate(f+offsetX, g+offsetY)} 在 angle=0 下的轴对齐 quad，运算顺序逐
     * 表达式照搬。UV 读精灵真实图集 UV（{@link SpriteUvAccessor}），原版
     * {@code var3} 恒 0.0F（0.001F 声明后立即被 0.0F 覆盖），加减 0.0f 为恒等。
     *
     * @param hasTexture 精灵纹理非 null；为 false 时原版 render no-op，只做 setSize
     */
    static void encodeHitGlowQuads(final Sprite hitGlow,
                                   final List<?> slots,
                                   final float sizeScale,
                                   final float glowSizeMult,
                                   final boolean hasTexture,
                                   final byte r, final byte g, final byte b, final byte a) {
        beginBatch();
        final SpriteUvAccessor uv = (SpriteUvAccessor) hitGlow;
        final float u0 = uv.ssoptimizer$getTexX();
        final float v0 = uv.ssoptimizer$getTexY();
        final float u1 = u0 + uv.ssoptimizer$getTexWidth();
        final float v1 = v0 + uv.ssoptimizer$getTexHeight();
        final int offsetX = hitGlow.getOffsetX();
        final int offsetY = hitGlow.getOffsetY();
        for (final Object o : slots) {
            final EngineGlowSlotAccessor slot = (EngineGlowSlotAccessor) o;
            // 原版：var21 = glowSize * var5 * glowSizeMult（:298），运算顺序照搬
            final float size = slot.ssoptimizer$getGlowSize() * sizeScale * glowSizeMult;
            hitGlow.setSize(size, size);
            if (!hasTexture) {
                continue;
            }
            if (numVertices + 4 > MAX_VERTICES) {
                // 极端槽数的中途 flush：client arrays 与纹理由调用方在编码前
                // 使能/绑定（正常舰船 1~6 槽永不触发）
                flushBatch();
            }
            final Vector2f offset = slot.ssoptimizer$getOffset();
            final float quadX = offset.x - size / 2.0f + offsetX;
            final float quadY = offset.y - size / 2.0f + offsetY;
            // 原版逐顶点序列：(0,0) (0,h) (w,h) (w,0)，UV 角点 (u0,v0)(u0,v1)(u1,v1)(u1,v0)
            putVertex(quadX, quadY, u0, v0, r, g, b, a);
            putVertex(quadX, quadY + size, u0, v1, r, g, b, a);
            putVertex(quadX + size, quadY + size, u1, v1, r, g, b, a);
            putVertex(quadX + size, quadY, u1, v0, r, g, b, a);
        }
    }

    private static void beginBatch() {
        ensureBuffers();
        colorBuf.clear();
        vertexBuf.clear();
        texCoordBuf.clear();
        numVertices = 0;
    }

    private static void ensureBuffers() {
        if (colorBuf != null) {
            return;
        }

        colorBuf = ByteBuffer.allocateDirect(MAX_VERTICES * 4)
                             .order(ByteOrder.nativeOrder());
        vertexBuf = ByteBuffer.allocateDirect(MAX_VERTICES * 2 * 4)
                              .order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuf = ByteBuffer.allocateDirect(MAX_VERTICES * 2 * 4)
                                .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static void putVertex(final float x, final float y,
                                  final float u, final float v,
                                  final byte r, final byte g, final byte b, final byte a) {
        colorBuf.put(r).put(g).put(b).put(a);
        texCoordBuf.put(u).put(v);
        vertexBuf.put(x).put(y);
        numVertices++;
    }

    private static void flushBatch() {
        if (numVertices == 0) {
            return;
        }

        colorBuf.flip();
        vertexBuf.flip();
        texCoordBuf.flip();

        GL11.glColorPointer(4, true, 0, colorBuf);
        GL11.glVertexPointer(2, 0, vertexBuf);
        GL11.glTexCoordPointer(2, 0, texCoordBuf);

        GL11.glDrawArrays(GL11.GL_QUADS, 0, numVertices);

        colorBuf.clear();
        vertexBuf.clear();
        texCoordBuf.clear();
        numVertices = 0;
    }

    // ------------------------------------------------------------------
    // 包内测试触点（同包测试直接验证批次内容；生产路径零成本）
    // ------------------------------------------------------------------

    /** 当前批次已编码的顶点数。 */
    static int getNumVertices() {
        return numVertices;
    }

    /** 第 index 个已编码顶点的完整内容（颜色按无符号字节展开）。 */
    static EncodedVertex vertexAt(final int index) {
        if (index < 0 || index >= numVertices) {
            throw new IndexOutOfBoundsException(
                    "vertex index " + index + " out of [0, " + numVertices + ")");
        }
        final int colorIndex = index * 4;
        final int coordIndex = index * 2;
        return new EncodedVertex(
                vertexBuf.get(coordIndex),
                vertexBuf.get(coordIndex + 1),
                texCoordBuf.get(coordIndex),
                texCoordBuf.get(coordIndex + 1),
                colorBuf.get(colorIndex) & 0xFF,
                colorBuf.get(colorIndex + 1) & 0xFF,
                colorBuf.get(colorIndex + 2) & 0xFF,
                colorBuf.get(colorIndex + 3) & 0xFF);
    }

    /** 已编码顶点内容（位置/UV/颜色）。 */
    record EncodedVertex(float x, float y, float u, float v, int r, int g, int b, int a) {
    }

    /**
     * 每槽辉光几何缓存：槽位 angle/offset/width/baseLength 为构造期定值，8 顶点
     * 坐标是 scale 元组（widthMult, lengthScale, engineHeightMult, fullBrightness）
     * 的纯函数，故按元组的 float 位级键（{@link Float#floatToRawIntBits}）缓存。
     * 命中时 {@code encodeSlot} 只重写颜色字节；miss（Fader/ValueShifter 过渡期逐帧
     * 变化）回退全编码并把算出的坐标写回。方向向量（cos/sin，构造期定值）独立于
     * 几何缓存只算一次。
     * <p>
     * 键用 floatToRawIntBits 规范化比较：不同 payload 的 NaN 折叠为同一键——
     * 引擎缩放正常取值域不含 NaN（上游有 NaN guard），且命中时发射的缓存坐标
     * 本身就是合法状态下的计算结果。
     * <p>
     * 存储由 {@code CampaignShipEngineGlowMixin} 的 @Unique 字段持有（每船一份），
     * 槽数变化时整体重建。
     */
    public static final class GlowGeometryCache {
        /** 每槽缓存键宽度：widthMult / lengthScale / engineHeightMult / fullBrightness。 */
        private static final int KEY_INTS_PER_SLOT = 4;
        /** 每槽缓存坐标宽度：8 顶点 × 2 分量。 */
        private static final int COORDS_PER_SLOT = 16;

        private final int slotCount;
        /** 位级键（floatToRawIntBits），{@code slotIndex * KEY_INTS_PER_SLOT} 起。 */
        private final int[] keys;
        /** 8 顶点坐标（x,y 交错，发射顺序即原版逐顶点顺序）。 */
        private final float[] coords;
        /** 每槽方向向量（dirX/dirY，构造期定值）。 */
        private final float[] directions;
        private final boolean[] geometryValid;
        private final boolean[] directionValid;

        public GlowGeometryCache(final int slotCount) {
            this.slotCount = slotCount;
            this.keys = new int[slotCount * KEY_INTS_PER_SLOT];
            this.coords = new float[slotCount * COORDS_PER_SLOT];
            this.directions = new float[slotCount * 2];
            this.geometryValid = new boolean[slotCount];
            this.directionValid = new boolean[slotCount];
        }

        /** 缓存对应的槽数（与 slots 列表不一致时由持有方整体重建）。 */
        public int slotCount() {
            return slotCount;
        }

        /** 方向向量是否已算过（构造期定值，只算一次）。 */
        boolean hasDirection(final int slotIndex) {
            return directionValid[slotIndex];
        }

        float directionX(final int slotIndex) {
            return directions[slotIndex * 2];
        }

        float directionY(final int slotIndex) {
            return directions[slotIndex * 2 + 1];
        }

        void storeDirection(final int slotIndex, final float dirX, final float dirY) {
            directions[slotIndex * 2] = dirX;
            directions[slotIndex * 2 + 1] = dirY;
            directionValid[slotIndex] = true;
        }

        /** scale 元组位级键命中判定。 */
        boolean isHit(final int slotIndex,
                      final int widthMultBits, final int lengthScaleBits,
                      final int heightMultBits, final int fullBrightnessBits) {
            if (!geometryValid[slotIndex]) {
                return false;
            }
            final int base = slotIndex * KEY_INTS_PER_SLOT;
            return keys[base] == widthMultBits
                    && keys[base + 1] == lengthScaleBits
                    && keys[base + 2] == heightMultBits
                    && keys[base + 3] == fullBrightnessBits;
        }

        /** miss 路径全编码后写回：坐标为调用方刚算出的值（与当次发射位级一致）。 */
        void storeGeometry(final int slotIndex,
                           final int widthMultBits, final int lengthScaleBits,
                           final int heightMultBits, final int fullBrightnessBits,
                           final float x0, final float y0, final float x1, final float y1,
                           final float x2, final float y2, final float x3, final float y3,
                           final float x4, final float y4, final float x5, final float y5,
                           final float x6, final float y6, final float x7, final float y7) {
            final int keyBase = slotIndex * KEY_INTS_PER_SLOT;
            keys[keyBase] = widthMultBits;
            keys[keyBase + 1] = lengthScaleBits;
            keys[keyBase + 2] = heightMultBits;
            keys[keyBase + 3] = fullBrightnessBits;
            final int coordBase = slotIndex * COORDS_PER_SLOT;
            coords[coordBase] = x0;
            coords[coordBase + 1] = y0;
            coords[coordBase + 2] = x1;
            coords[coordBase + 3] = y1;
            coords[coordBase + 4] = x2;
            coords[coordBase + 5] = y2;
            coords[coordBase + 6] = x3;
            coords[coordBase + 7] = y3;
            coords[coordBase + 8] = x4;
            coords[coordBase + 9] = y4;
            coords[coordBase + 10] = x5;
            coords[coordBase + 11] = y5;
            coords[coordBase + 12] = x6;
            coords[coordBase + 13] = y6;
            coords[coordBase + 14] = x7;
            coords[coordBase + 15] = y7;
            geometryValid[slotIndex] = true;
        }

        /** 第 slotIndex 槽缓存几何的第 i 个 float（0..15，x,y 交错）。 */
        float geometryAt(final int slotIndex, final int i) {
            return coords[slotIndex * COORDS_PER_SLOT + i];
        }
    }
}
