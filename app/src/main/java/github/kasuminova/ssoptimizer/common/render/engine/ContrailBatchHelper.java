package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.mixin.accessor.ContrailGroupAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.ContrailSegmentAccessor;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Map;

/**
 * Batches original engine {@code ContrailEngine} strips and flushes them with
 * {@code glDrawArrays(GL_QUAD_STRIP)} calls.
 * <p>
 * 原版渲染器已经按（纹理、混合模式）分组（{@code ContrailGroup}），且每组的纹理
 * 都来自同一路径 {@code graphics/fx/contrail64b.png}（经进程级 {@code oOoO} 缓存
 * 取同一 {@code TextureObject} 实例）。本 helper 在此基础上做一次合并：
 * <ul>
 *   <li><b>批次合并</b>：混合模式相同的相邻组编码进同一条 {@code GL_QUAD_STRIP}，
 *       组间以 2 个退化顶点连接（重复上一组末顶点两次，零面积四边形不产生碎片），
 *       把「每组的 1 次 draw」折叠为「每段同混合模式连续组的 1 次 draw」——
 *       录制侧逐组的 buffer 快照拷贝（{@code glColorPointer/glVertexPointer/
 *       glTexCoordPointer} 各一次深拷贝）、draw 命令借出与 pointer 快照组捕获
 *       全部随之摊销；</li>
 *   <li><b>录制路径瘦身</b>：client attrib 压栈/出栈与三个 client array 使能
 *       从「每组一次」提升到「整次渲染一次」；纹理绑定仅在纹理实例变化时发生
 *       （原版逐组无条件 bind 同一纹理）；当前颜色恢复从「每组一次」收敛为
 *       「整次渲染结束一次」（组间无 immediate 绘制，current color 不被读取，
 *       仅最后一段的恢复对外可见，与原版行为一致）。</li>
 * </ul>
 * <b>时序语义</b>（v37-v43 黑屏教训：draw 命令不得数组化/延迟化，录制必须保持
 * 命令的时序语义）：本 helper 不做任何命令数组化——所有状态命令与 draw 命令仍
 * 按录制顺序逐条入队（bridge 侧 {@code glCallList} 等仍消费调用时刻的 current
 * 状态）；合并只改变「同混合模式连续组共享一次 draw」的边界，组间相对顺序、
 * 段内顶点顺序与原版逐组绘制逐指令等价。混合模式切换处先 flush 当前批次再切换，
 * 批次顺序即组的遍历顺序，不重排任何 draw。
 */
public final class ContrailBatchHelper {
    private static final int   MAX_VERTICES           = 262_144;
    private static final float V_MIN                  = 0.01f;
    private static final float V_MAX                  = 0.99f;
    private static final int   GL_SRC_ALPHA           = 770;
    private static final int   GL_ONE                 = 1;
    private static final int   GL_ONE_MINUS_SRC_ALPHA = 771;

    private static ByteBuffer  colorBuf;
    private static FloatBuffer vertexBuf;
    private static FloatBuffer texCoordBuf;
    private static int         numVertices;

    /** 批次是否已 flush 过（最终 current color 恢复只在有绘制时发生）。 */
    private static boolean hasFlushedStrip;
    /** 最近一次 flush 的末顶点颜色（最终 current color 恢复的来源）。 */
    private static byte finalR;
    private static byte finalG;
    private static byte finalB;
    private static byte finalA;
    /**
     * 批次连接点需要的「上一对段末顶点」（恒为右顶点，V=V_MAX）：同混合模式组
     * 拼接进同一 strip 时重复写入两次，形成零面积退化四边形。
     */
    private static float lastU;
    private static float lastX;
    private static float lastY;
    private static byte  lastR;
    private static byte  lastG;
    private static byte  lastB;
    private static byte  lastA;

    private ContrailBatchHelper() {
    }

    public static void renderContrails(Object groupsObject, float alphaScale) {
        if (!(groupsObject instanceof Map<?, ?> groups) || groups.isEmpty()) {
            return;
        }

        try {
            renderAllGroups(groups, alphaScale);
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to render ContrailEngine group", e);
        }
    }

    /**
     * 整次渲染：client attrib 压栈一次、三个 client array 使能一次，随后按组的
     * 遍历顺序编码。混合模式变化处结束当前批次（flush 一次 draw）并切换混合，
     * 纹理仅在实例变化时重新绑定；同混合模式的相邻组合并为同一条 strip。
     */
    private static void renderAllGroups(Map<?, ?> groups, float alphaScale) {
        beginStrip();
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

            Object activeBlend = new Object();
            Object boundTexture = null;
            for (Object groupObject : groups.values()) {
                ContrailGroupAccessor group = (ContrailGroupAccessor) groupObject;
                if (group == null) {
                    continue;
                }

                // 混合模式变化 = 批次边界：先结束当前 strip，再切换混合。
                // 不可绘制组同样推进 blend 状态（原版对每个组无条件调用 glBlendFunc）。
                Object blend = group.ssoptimizer$getBlendMode();
                if (activeBlend != blend) {
                    flushStrip();
                    applyBlendMode(blend);
                    activeBlend = blend;
                }

                com.fs.graphics.TextureObject texture = group.ssoptimizer$getTexture();
                if (texture == null) {
                    continue;
                }
                List<Object> segments = group.ssoptimizer$getSegments();
                if (segments == null || segments.size() <= 1) {
                    continue;
                }
                if (boundTexture != texture) {
                    texture.bind();
                    boundTexture = texture;
                }

                if (!encodeGroup(group, alphaScale)) {
                    flushStrip();
                    restoreCurrentColor();
                    return;
                }
            }

            flushStrip();
            restoreCurrentColor();
        } finally {
            GL11.glPopClientAttrib();
        }
    }

    /**
     * 把一个组的全部段与尾点编码进当前批次缓冲。批次非空（已有其他组）时先补
     * 连接点：重复上一组末顶点两次，GL_QUAD_STRIP 中形成两个零面积退化四边形，
     * 之后的段按原顶点顺序继续，不产生任何连接碎片。
     *
     * @return false 表示组数据不完整且尾点法线缺失，调用方应结束当前批次并中止
     *         后续组（原版逐组 flush+return 的同路径行为）
     */
    static boolean encodeGroup(ContrailGroupAccessor group, float alphaScale) {
        Color color = group.ssoptimizer$getColor();
        if (color == null) {
            return true;
        }
        Vector2f tailPoint = group.ssoptimizer$getTail();
        int red = color.getRed();
        int green = color.getGreen();
        int blue = color.getBlue();
        int baseAlpha = color.getAlpha();

        emitJoinConnector();

        ContrailSegmentAccessor lastSegment = null;
        for (Object segmentObject : group.ssoptimizer$getSegments()) {
            ContrailSegmentAccessor segment = (ContrailSegmentAccessor) segmentObject;

            float maxAge = segment.ssoptimizer$getMaxAge();
            float fadeWindow = maxAge <= 0.0f ? 0.5f : 0.05f / maxAge;
            if (fadeWindow > 0.5f) {
                fadeWindow = 0.5f;
            }

            float progress = segment.ssoptimizer$getProgress();
            float brightness;
            if (progress < fadeWindow) {
                brightness = progress * 10.0f;
            } else {
                brightness = (1.0f - progress) / (1.0f - fadeWindow);
            }
            brightness *= alphaScale;

            int alpha = clampColorComponent((int) (baseAlpha
                    * segment.ssoptimizer$getAlphaMult()
                    * brightness));

            Vector2f position = segment.ssoptimizer$getPosition();
            Vector2f normal = segment.ssoptimizer$getNormal();
            if (position == null || normal == null) {
                continue;
            }
            float width = segment.ssoptimizer$getWidth();
            float halfWidth = width * 0.5f;

            addPair(
                    red, green, blue, alpha,
                    segment.ssoptimizer$getU(),
                    position.x - normal.x * halfWidth,
                    position.y - normal.y * halfWidth,
                    position.x + normal.x * halfWidth,
                    position.y + normal.y * halfWidth
            );
            lastSegment = segment;
        }

        if (tailPoint != null && lastSegment != null) {
            Vector2f normal = lastSegment.ssoptimizer$getNormal();
            if (normal == null) {
                return false;
            }
            float width = lastSegment.ssoptimizer$getWidth() * 0.25f;
            float u = lastSegment.ssoptimizer$getU();

            addPair(
                    red, green, blue, 0,
                    u,
                    tailPoint.x - normal.x * width,
                    tailPoint.y - normal.y * width,
                    tailPoint.x + normal.x * width,
                    tailPoint.y + normal.y * width
            );
        }
        return true;
    }

    static void beginStrip() {
        ensureBuffers();
        colorBuf.clear();
        vertexBuf.clear();
        texCoordBuf.clear();
        numVertices = 0;
        hasFlushedStrip = false;
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

    /**
     * 批次非空时补连接点：重复上一对段末顶点（右顶点）两次。批次为空（本组是
     * 当前批次第一组）时无连接点。
     */
    private static void emitJoinConnector() {
        if (numVertices == 0) {
            return;
        }
        if (numVertices + 2 > MAX_VERTICES) {
            flushStrip();
            return;
        }
        putVertex(lastU, lastX, lastY, lastR, lastG, lastB, lastA);
        putVertex(lastU, lastX, lastY, lastR, lastG, lastB, lastA);
    }

    /**
     * 写入单个顶点（连接点用）：不更新 {@link #lastU}/{@link #lastX} 等末顶点
     * 跟踪——连接点的重复顶点不是新的段末顶点。
     */
    private static void putVertex(float u, float x, float y, byte r, byte g, byte b, byte a) {
        colorBuf.put(r).put(g).put(b).put(a);
        texCoordBuf.put(u).put(V_MAX);
        vertexBuf.put(x).put(y);
        numVertices++;
    }

    private static void addPair(int r, int g, int b, int a,
                                float u,
                                float leftX, float leftY,
                                float rightX, float rightY) {
        if (numVertices + 2 > MAX_VERTICES) {
            flushStrip();
        }

        byte rb = (byte) r;
        byte gb = (byte) g;
        byte bb = (byte) b;
        byte ab = (byte) a;

        colorBuf.put(rb).put(gb).put(bb).put(ab);
        colorBuf.put(rb).put(gb).put(bb).put(ab);

        texCoordBuf.put(u).put(V_MIN);
        texCoordBuf.put(u).put(V_MAX);

        vertexBuf.put(leftX).put(leftY);
        vertexBuf.put(rightX).put(rightY);

        numVertices += 2;

        lastU = u;
        lastX = rightX;
        lastY = rightY;
        lastR = rb;
        lastG = gb;
        lastB = bb;
        lastA = ab;
    }

    private static void flushStrip() {
        if (numVertices == 0) {
            return;
        }

        colorBuf.flip();
        vertexBuf.flip();
        texCoordBuf.flip();

        // 捕获本段末顶点颜色供最终 current color 恢复（不在本段内恢复——
        // 组间无 immediate 绘制，只有整次渲染结束时的恢复对外可见）。
        // client attrib 压栈/出栈与 client array 使能已提升到整次渲染一次
        // （renderAllGroups），本段只负责 pointer 设置与 draw。
        int finalColorIndex = (numVertices - 1) * 4;
        finalR = colorBuf.get(finalColorIndex);
        finalG = colorBuf.get(finalColorIndex + 1);
        finalB = colorBuf.get(finalColorIndex + 2);
        finalA = colorBuf.get(finalColorIndex + 3);
        hasFlushedStrip = true;

        GL11.glColorPointer(4, true, 0, colorBuf);
        GL11.glVertexPointer(2, 0, vertexBuf);
        GL11.glTexCoordPointer(2, 0, texCoordBuf);

        GL11.glDrawArrays(GL11.GL_QUAD_STRIP, 0, numVertices);

        colorBuf.clear();
        vertexBuf.clear();
        texCoordBuf.clear();
        numVertices = 0;
    }

    /**
     * 整次渲染结束时的 current color 恢复：把当前颜色设为本批次最后一段的末顶点
     * 颜色（原版逐组绘制结束时 current color 同样为该组末顶点颜色——尾点对
     * alpha 为 0）。无任何绘制（全部组不可绘制）时不调用 glColor4ub，与原版一致。
     */
    private static void restoreCurrentColor() {
        if (!hasFlushedStrip) {
            return;
        }
        GL11.glColor4ub(finalR, finalG, finalB, finalA);
    }

    private static void applyBlendMode(Object blendMode) {
        if (isGlowBlendMode(blendMode)) {
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        } else {
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
    }

    static boolean isGlowBlendMode(Object blendMode) {
        return blendMode instanceof Enum<?> mode && "GLOW".equals(mode.name());
    }

    static int clampColorComponent(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 255);
    }

    // ------------------------------------------------------------------
    // 包内测试触点（同包测试直接验证批次内容；生产路径零成本）
    // ------------------------------------------------------------------

    /** 当前批次已编码的顶点数。 */
    static int getNumVertices() {
        return numVertices;
    }

    /** 第 index 个已编码顶点的完整内容（颜色按无符号字节展开）。 */
    static EncodedVertex vertexAt(int index) {
        if (index < 0 || index >= numVertices) {
            throw new IndexOutOfBoundsException(
                    "vertex index " + index + " out of [0, " + numVertices + ")");
        }
        int colorIndex = index * 4;
        int coordIndex = index * 2;
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
}
