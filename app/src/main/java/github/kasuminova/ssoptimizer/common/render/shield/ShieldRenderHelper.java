package github.kasuminova.ssoptimizer.common.render.shield;

import com.fs.graphics.TextureObject;
import com.fs.graphics.util.RenderStateUtils;
import github.kasuminova.ssoptimizer.common.render.spritebatch.SpriteBatch;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.logging.Logger;

/**
 * 舰船护盾渲染合批助手。
 * <p>
 * 整体替换原版 {@code com.fs.starfarer.combat.systems.Shield#render(float)} 的立即模式绘制：
 * 两遍 additive TRIANGLE_FAN（每遍 segmentCount 次三角函数 + 逐顶点 JNI）合并为单次
 * {@code glDrawArrays(GL_TRIANGLES)}，band noise 条带以单次 {@code glDrawArrays(GL_QUAD_STRIP)} 提交。
 * 两遍 additive 合并的依据：additive 混合满足交换律，且展开后的三角形片元集合与两个扇形完全一致。
 * <p>
 * 不复刻的死代码（依据原版源码循环上界恒为 2，永不触发）：
 * 原版 render 循环中 {@code var8 >= 2} 的 ringTexture 第三遍绑定与 π/4 相位 UV 分支。
 * <p>
 * 以下两种场景回退到逐行复刻原版的立即模式路径（{@link #renderImmediate}）：
 * <ul>
 *   <li>JVM 参数 {@code -Dssoptimizer.render.shield.enable=false} 关停优化；</li>
 *   <li>检测到正在编译 GL 显示列表（GL_LIST_INDEX != 0，即 Ship 抖动特效把护盾编进 display list），
 *       因为 client 侧顶点数组状态与显示列表语义无可靠保证。</li>
 * </ul>
 */
public final class ShieldRenderHelper {
    /** 优化开关属性：{@code -Dssoptimizer.render.shield.enable=false} 关停，默认开启。 */
    public static final String ENABLE_PROPERTY = "ssoptimizer.render.shield.enable";

    private static final Logger LOGGER = Logger.getLogger(ShieldRenderHelper.class.getName());

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "true"));

    private static final int GL_TEXTURE_2D           = 3553;
    private static final int GL_BLEND                = 3042;
    private static final int GL_SRC_ALPHA            = 770;
    private static final int GL_ONE                  = 1;
    private static final int GL_ONE_MINUS_SRC_ALPHA  = 771;

    // 复用的 direct 缓冲与 CPU 侧暂存数组（渲染单线程访问）
    private static ByteBuffer  colorBuf;
    private static FloatBuffer vertexBuf;
    private static FloatBuffer texCoordBuf;
    private static int         bufferVertexCapacity;
    private static float[]     fanUv        = new float[ShieldArcGeometry.INITIAL_MAX_SEGMENTS * 2];
    private static float[]     bandVertices = new float[ShieldArcGeometry.INITIAL_MAX_SEGMENTS * 4];

    static {
        LOGGER.info("[SSOptimizer] Shield render optimization enabled=" + ENABLED
                + ", algo=" + (ShieldArcGeometry.useRaycast() ? "raycast" : "recurrence")
                + " (toggle: -D" + ENABLE_PROPERTY + ", -D" + ShieldArcGeometry.ALGO_PROPERTY + ")");
    }

    private ShieldRenderHelper() {
    }

    /**
     * 返回护盾渲染优化是否启用。
     *
     * @return 默认 true，{@code -Dssoptimizer.render.shield.enable=false} 时为 false
     */
    public static boolean isEnabled() {
        return ENABLED;
    }

    /**
     * 护盾渲染入参（可变数据载体，由 Mixin 每帧填充复用，避免逐帧分配）。
     * 字段语义与原版 Shield 同名成员一一对应。
     */
    public static final class Params {
        /** 原版 render(float) 的帧参数 amount。 */
        public float amount;
        /** 护盾基础弧角（度）。 */
        public float arc;
        /** 护盾半径。 */
        public float radius;
        /** 分段数。 */
        public int segmentCount;
        /** 每分段受击衰减值。 */
        public float[] segmentAlpha;
        /** segmentAlpha 上限。 */
        public float segmentAlphaMax;
        /** 内圈颜色。 */
        public Color innerColor;
        /** 外圈（band）颜色。 */
        public Color ringColor;
        /** band noise 相位角。 */
        public float ringAngle;
        /** 内圈纹理旋转角。 */
        public float innerAngle;
        /** band noise 幅度基准。 */
        public float textureScale;
        /** band 是否使用 additive 混合。 */
        public boolean renderAdditive;
        /** 母舰是否为战机（band 宽 3）。 */
        public boolean shipFighter;
        /** 母舰是否为护卫舰（band 宽 4，优先级高于战机分支，与原版一致）。 */
        public boolean shipFrigate;
        /** 护盾相对舰船的偏移 X（原版 var5）。 */
        public float offsetX;
        /** 护盾相对舰船的偏移 Y（原版 var6）。 */
        public float offsetY;
        /** 护盾朝向（aimTracker.getFacing()）。 */
        public float facing;
        /** chargeTracker.getChargeLevel()。 */
        public float chargeLevel;
        /** chargeTracker.getDamageMult()。 */
        public float chargeDamageMult;
        /** effectFader 亮度，fader 为 null 时为 0。 */
        public float effectBrightness;
        /** 特效强度。 */
        public float effectStrength;
        /** 特效 band 宽度系数。 */
        public float effectSizeMult;
        /** 特效 noise 幅度系数。 */
        public float effectRadiusMult;
        /** 内圈纹理。 */
        public TextureObject innerTexture;
        /** band 纹理。 */
        public TextureObject bandTexture;
    }

    /**
     * 渲染护盾（含展开弧角检查，语义与原版 render 一致；skipRendering 由调用方 Mixin 先行判断）。
     *
     * @param p 渲染入参
     */
    public static void render(final Params p) {
        // 原版：if (!this.skipRendering) { ... }
        // Sprite 合批顺序边界：护盾（非 sprite 绘制）前 flush 已累积批次
        SpriteBatch.getInstance().flushPending();
        float arcDeg = (p.arc + 10.0F) * p.chargeLevel;
        if (arcDeg <= 0.0F) {
            return;
        }

        if (ENABLED && GL11.glGetInteger(GL11.GL_LIST_INDEX) == 0) {
            renderBatched(p, arcDeg);
        } else {
            renderImmediate(p, arcDeg);
        }
    }

    /**
     * 合批路径：两遍 additive 扇形合并为单次 TRIANGLES，band 为单次 QUAD_STRIP。
     */
    private static void renderBatched(final Params p, final float arcDeg) {
        int segmentCount = p.segmentCount;
        float damageMult = p.chargeDamageMult * p.amount;
        float startDeg = p.facing - arcDeg / 2.0F;

        ensureCapacity(segmentCount);

        p.innerTexture.bind();
        GL11.glEnable(GL_TEXTURE_2D);
        GL11.glEnable(GL_BLEND);
        GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        GL11.glPushMatrix();
        GL11.glTranslatef(p.offsetX, p.offsetY, 0.0F);
        GL11.glRotatef(startDeg, 0.0F, 0.0F, 1.0F);

        // 两遍 additive FAN（原版 var8 = 0/1，仅 UV 相位符号不同）合并展开为 TRIANGLES
        float[] perimeter = ShieldArcGeometry.fanVertices(arcDeg, segmentCount, p.radius);
        float uvShift = (float) Math.toRadians(arcDeg / 2.0F);
        int red = p.innerColor.getRed();
        int green = p.innerColor.getGreen();
        int blue = p.innerColor.getBlue();
        int colorAlpha = p.innerColor.getAlpha();

        colorBuf.clear();
        vertexBuf.clear();
        texCoordBuf.clear();

        for (int pass = 0; pass < 2; pass++) {
            // 原版：var10 = innerAngle；第二遍取负；再减去 toRadians(arcDeg / 2)
            float phase = (pass == 1 ? -p.innerAngle : p.innerAngle) - uvShift;
            ShieldArcGeometry.fillFanTexCoords(fanUv, arcDeg, segmentCount, phase);

            for (int k = 0; k < segmentCount - 1; k++) {
                putFanCenter(red, green, blue);
                putFanVertex(perimeter, fanUv, p, k, arcDeg, damageMult, red, green, blue, colorAlpha);
                putFanVertex(perimeter, fanUv, p, k + 1, arcDeg, damageMult, red, green, blue, colorAlpha);
            }
        }

        flush(GL11.GL_TRIANGLES);
        GL11.glPopMatrix();

        renderBandBatched(p, arcDeg, damageMult, startDeg);
    }

    /**
     * 合批路径的 band 条带，对应原版 renderBand。
     */
    private static void renderBandBatched(final Params p, final float arcDeg,
                                          final float damageMult, final float startDeg) {
        int segmentCount = p.segmentCount;

        // 原版：var7 = 5；战机 3；护卫舰 4（后判覆盖先判，原样保留）
        float bandWidth = 5.0F;
        if (p.shipFighter) {
            bandWidth = 3.0F;
        }
        if (p.shipFrigate) {
            bandWidth = 4.0F;
        }
        bandWidth = bandWidth + bandWidth * p.effectStrength * p.effectSizeMult * p.effectBrightness;

        float scaleEff = p.textureScale + p.textureScale * p.effectStrength * p.effectRadiusMult * p.effectBrightness;
        float bandArcRad = ShieldArcGeometry.bandArcRadians(startDeg, arcDeg);

        GL11.glPushMatrix();
        GL11.glTranslatef(p.offsetX, p.offsetY, 0.0F);
        GL11.glRotatef(startDeg, 0.0F, 0.0F, 1.0F);
        GL11.glEnable(GL_TEXTURE_2D);
        p.bandTexture.bind();
        GL11.glEnable(GL_BLEND);
        if (p.renderAdditive) {
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        } else {
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }

        ShieldArcGeometry.fillBandStrip(bandVertices, segmentCount, bandArcRad,
                p.radius, bandWidth, scaleEff, p.ringAngle);

        int red = p.ringColor.getRed();
        int green = p.ringColor.getGreen();
        int blue = p.ringColor.getBlue();

        colorBuf.clear();
        vertexBuf.clear();
        texCoordBuf.clear();

        for (int k = 0; k < segmentCount; k++) {
            int alpha = ShieldArcGeometry.bandAlpha(k, segmentCount, bandArcRad,
                    damageMult, p.segmentAlpha[k], p.segmentAlphaMax);
            byte rb = (byte) red;
            byte gb = (byte) green;
            byte bb = (byte) blue;
            byte ab = (byte) alpha;
            int base = k * 4;

            // 外圈顶点：UV (0, 0)
            colorBuf.put(rb).put(gb).put(bb).put(ab);
            texCoordBuf.put(0.0F).put(0.0F);
            vertexBuf.put(bandVertices[base]).put(bandVertices[base + 1]);

            // 内圈顶点：UV (0, 1)
            colorBuf.put(rb).put(gb).put(bb).put(ab);
            texCoordBuf.put(0.0F).put(1.0F);
            vertexBuf.put(bandVertices[base + 2]).put(bandVertices[base + 3]);
        }

        flush(GL11.GL_QUAD_STRIP);
        GL11.glPopMatrix();
    }

    /**
     * 立即模式路径：逐行复刻原版 render + renderBand（含颜色/alpha 公式与 GL 调用顺序）。
     * 仅在优化关停或显示列表编译期间使用（后者是 Ship 抖动特效的一次性编译，频率极低）。
     */
    private static void renderImmediate(final Params p, final float arcDeg) {
        float damageMult = p.chargeDamageMult * p.amount;
        float startDeg = p.facing - arcDeg / 2.0F;

        p.innerTexture.bind();
        GL11.glEnable(GL_TEXTURE_2D);
        GL11.glEnable(GL_BLEND);
        GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        GL11.glPushMatrix();
        GL11.glTranslatef(p.offsetX, p.offsetY, 0.0F);
        GL11.glRotatef(startDeg, 0.0F, 0.0F, 1.0F);

        // 原版 for (int var8 = 0; var8 < 2; var8++)：var8 >= 2 的 ringTexture 第三遍永不触发，不复刻
        for (int pass = 0; pass < 2; pass++) {
            float phase = pass == 1 ? -p.innerAngle : p.innerAngle;
            phase -= (float) Math.toRadians(arcDeg / 2.0F);
            GL11.glBegin(GL11.GL_TRIANGLE_FAN);
            RenderStateUtils.setGlColor(p.innerColor, 0);
            GL11.glTexCoord2f(0.5F, 0.5F);
            GL11.glVertex2f(0.0F, 0.0F);
            float segments = p.segmentCount - 1;
            float delta = (float) Math.toRadians(arcDeg) / segments;
            float fanRadius = p.radius * 1.07F;

            for (int k = 0; k < p.segmentCount; k++) {
                int alpha = ShieldArcGeometry.fanVertexAlpha(k, p.segmentCount, arcDeg,
                        damageMult, p.segmentAlpha[k], p.segmentAlphaMax, p.innerColor.getAlpha());
                RenderStateUtils.setGlColor(p.innerColor, alpha);

                float angle = k * delta;
                GL11.glTexCoord2f(
                        0.5F + (float) Math.cos(angle + phase) / 2.0F,
                        0.5F + (float) Math.sin(angle + phase) / 2.0F);
                GL11.glVertex2f(fanRadius * (float) Math.cos(angle), fanRadius * (float) Math.sin(angle));
            }

            GL11.glEnd();
        }

        GL11.glPopMatrix();
        renderBandImmediate(p, arcDeg, damageMult, startDeg);
    }

    /**
     * 立即模式路径的 band 条带，逐行复刻原版 renderBand。
     */
    private static void renderBandImmediate(final Params p, final float arcDeg,
                                            final float damageMult, final float startDeg) {
        float bandWidth = 5.0F;
        if (p.shipFighter) {
            bandWidth = 3.0F;
        }
        if (p.shipFrigate) {
            bandWidth = 4.0F;
        }
        bandWidth = bandWidth + bandWidth * p.effectStrength * p.effectSizeMult * p.effectBrightness;

        float bandArcRad = ShieldArcGeometry.bandArcRadians(startDeg, arcDeg);
        float segments = p.segmentCount - 1;
        float delta = bandArcRad / segments;

        GL11.glPushMatrix();
        GL11.glTranslatef(p.offsetX, p.offsetY, 0.0F);
        GL11.glRotatef(startDeg, 0.0F, 0.0F, 1.0F);
        GL11.glEnable(GL_TEXTURE_2D);
        p.bandTexture.bind();
        GL11.glEnable(GL_BLEND);
        if (p.renderAdditive) {
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        } else {
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }

        GL11.glBegin(GL11.GL_QUAD_STRIP);
        for (int k = 0; k < p.segmentCount; k++) {
            float scaleEff = p.textureScale + p.textureScale * p.effectStrength * p.effectRadiusMult * p.effectBrightness;
            float noise = scaleEff * (float) Math.sin(p.ringAngle * 10.0F + k * delta * 10.0F);
            float rOut = p.radius + noise;
            int alpha = ShieldArcGeometry.bandAlpha(k, p.segmentCount, bandArcRad,
                    damageMult, p.segmentAlpha[k], p.segmentAlphaMax);
            RenderStateUtils.setGlColor(p.ringColor, alpha);
            float angle = delta * k;
            float c = (float) Math.cos(angle);
            float s = (float) Math.sin(angle);
            GL11.glTexCoord2f(0.0F, 0.0F);
            GL11.glVertex2f(c * rOut, s * rOut);
            GL11.glTexCoord2f(0.0F, 1.0F);
            GL11.glVertex2f(c * (rOut - bandWidth), s * (rOut - bandWidth));
        }
        GL11.glEnd();
        GL11.glPopMatrix();
    }

    /**
     * 确保 direct 缓冲与 CPU 暂存数组容量足够（初始按 256 段分配，超出时倍增并记录日志）。
     */
    private static void ensureCapacity(final int segmentCount) {
        // 扇形两遍展开为 TRIANGLES：2 × 3 × (segmentCount - 1)；band：2 × segmentCount
        int required = Math.max(2 * 3 * (segmentCount - 1), 2 * segmentCount);
        if (colorBuf != null && required <= bufferVertexCapacity) {
            return;
        }

        int newCapacity = Math.max(ShieldArcGeometry.INITIAL_MAX_SEGMENTS * 6, bufferVertexCapacity * 2);
        while (newCapacity < required) {
            newCapacity *= 2;
        }
        LOGGER.info("[SSOptimizer] Shield render buffers grow to " + newCapacity + " vertices (segmentCount="
                + segmentCount + ")");

        colorBuf = ByteBuffer.allocateDirect(newCapacity * 4).order(ByteOrder.nativeOrder());
        vertexBuf = ByteBuffer.allocateDirect(newCapacity * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuf = ByteBuffer.allocateDirect(newCapacity * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        bufferVertexCapacity = newCapacity;

        if (fanUv.length < segmentCount * 2) {
            fanUv = new float[segmentCount * 2];
        }
        if (bandVertices.length < segmentCount * 4) {
            bandVertices = new float[segmentCount * 4];
        }
    }

    private static void putFanCenter(final int red, final int green, final int blue) {
        // 原版扇心：setGlColor(color, 0)、UV (0.5, 0.5)、坐标 (0, 0)
        colorBuf.put((byte) red).put((byte) green).put((byte) blue).put((byte) 0);
        texCoordBuf.put(0.5F).put(0.5F);
        vertexBuf.put(0.0F).put(0.0F);
    }

    private static void putFanVertex(final float[] perimeter, final float[] uv, final Params p, final int k,
                                     final float arcDeg, final float damageMult,
                                     final int red, final int green, final int blue, final int colorAlpha) {
        int alpha = ShieldArcGeometry.fanVertexAlpha(k, p.segmentCount, arcDeg,
                damageMult, p.segmentAlpha[k], p.segmentAlphaMax, colorAlpha);
        colorBuf.put((byte) red).put((byte) green).put((byte) blue).put((byte) alpha);
        texCoordBuf.put(uv[k * 2]).put(uv[k * 2 + 1]);
        vertexBuf.put(perimeter[k * 2]).put(perimeter[k * 2 + 1]);
    }

    /**
     * 提交当前缓冲内容，并恢复 client 状态与当前颜色（与原版立即模式的收尾状态一致）。
     */
    private static void flush(final int mode) {
        int numVertices = vertexBuf.position() / 2;
        if (numVertices == 0) {
            return;
        }

        colorBuf.flip();
        vertexBuf.flip();
        texCoordBuf.flip();

        int finalColorIndex = (numVertices - 1) * 4;
        byte finalRed = colorBuf.get(finalColorIndex);
        byte finalGreen = colorBuf.get(finalColorIndex + 1);
        byte finalBlue = colorBuf.get(finalColorIndex + 2);
        byte finalAlpha = colorBuf.get(finalColorIndex + 3);

        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

            GL11.glColorPointer(4, true, 0, colorBuf);
            GL11.glVertexPointer(2, 0, vertexBuf);
            GL11.glTexCoordPointer(2, 0, texCoordBuf);

            GL11.glDrawArrays(mode, 0, numVertices);
        } finally {
            GL11.glPopClientAttrib();
        }

        // glDrawArrays 后主颜色不确定，显式恢复为最后一个顶点的颜色，贴合原版立即模式收尾状态
        GL11.glColor4ub(finalRed, finalGreen, finalBlue, finalAlpha);
    }
}
