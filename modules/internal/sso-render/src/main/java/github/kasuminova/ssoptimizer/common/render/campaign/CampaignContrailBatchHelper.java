package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import com.fs.starfarer.loading.specs.EngineSlot;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Map;

/**
 * 战役舰队尾迹（{@code ContrailEngineV2}）的合批渲染：把原版逐条
 * {@code glBegin(GL_QUAD_STRIP)} + 逐点 immediate 发射折叠为按混合模式分段的
 * {@code glDrawArrays(GL_QUAD_STRIP)}，范式与战斗侧
 * {@link github.kasuminova.ssoptimizer.common.render.engine.ContrailBatchHelper}
 * 一致（静态直接缓冲 + 组间 2 个退化顶点连接）。
 * <p>
 * 与原版 {@code ContrailEngineV2.render(float)} 的语义对照（反编译 named 仓
 * {@code ContrailEngineV2.java:264-416} 逐行核对）：
 * <ul>
 *   <li><b>绘制模式同为 GL_QUAD_STRIP</b>：原版 {@code glBegin(8)}，连接点复用
 *       战斗侧「重复上一组末顶点两次」的零面积退化四边形方案；</li>
 *   <li><b>混合模式</b>：原版对每条尾迹（含不可绘制的）无条件
 *       {@code glBlendFunc}，GLOW→(770,1)、其余→(770,771)。本实现不可绘制尾迹
 *       同样推进 blend 状态跟踪，仅在状态变化时先 flush 再切换，draw 时刻的
 *       混合状态与原版逐条绘制完全一致；</li>
 *   <li><b>纹理绑定</b>：原版整次 render 只在第一条可绘制尾迹处 bind 一次
 *       （{@code var3} 标志），全部尾迹共享 {@code graphics/fx/contrail64b.png}
 *       的进程级缓存实例。本实现同样只在第一条可绘制尾迹处 bind；</li>
 *   <li><b>proximity 淡出引用点跨尾迹存续</b>：原版 {@code var4}（最近 fadeOut
 *       点）声明在 render 方法级、跨尾迹不清空，本实现以参数/返回值在
 *       {@link #encodeContrail} 之间传递，逐点公式与判定顺序位级一致；</li>
 *   <li><b>渲染期状态改写保留</b>：fadeOut 检测（segmentIntersection 展开段相交、
 *       前点折返 dot 判定）会写回 {@code fadeOut/origMax/elapsedWhenFadeOut}，
 *       proximity 会写回 {@code lastProximityMult}，不可绘制尾迹（点数 ≤ 1）会把
 *       全部点 {@code maxBrightness} 清零——这些副作用原版逐条发生，本实现逐项
 *       保留。展开段相交检测前加包围圆拒绝（dist² > ((wA+wB)×0.75)² 时两圆不相交
 *       ⇒ 展开段必不相交，perp 恒单位向量），拒绝路径与原「检测必为 false」路径
 *       的 markFadeOut 副作用完全一致（均不触发）；</li>
 *   <li><b>老化零亮度点简化路径</b>：{@code fadeOut} 已标记且 {@code maxBrightness}
 *       == 0 的点（encode 期间 maxBrightness 无人改写，恒 0），其 alpha 字节与
 *       亮度公式取值无关恒为 0、相交/折返检测的 markFadeOut 对已 fadeOut 点恒为
 *       no-op、proximity 在 fadeSource 更新后恒为本点（dist 0 ⇒ mult 精确 0），
 *       故跳过亮度除法/相交检测/sqrt，直接写零亮度顶点；fadeSource 更新与
 *       {@code lastProximityMult} 写回逐字保留（见 {@link #encodeContrail} 内注释）；</li>
 *   <li><b>无分配</b>：{@code segmentIntersection} 只需要 null/非 null 判定，展开为
 *       同表达式同顺序的标量 {@link #segmentsIntersect}；亮度/距离计算全部标量化，
 *       不再逐点 new Vector2f；</li>
 *   <li><b>RT 兼容</b>：只发出 native/stream 指令（client array + draw + bind +
 *       blend + 最终 current color 恢复），无 glGet* 回读。</li>
 * </ul>
 * <b>与战斗侧不共用的原因</b>：两引擎点结构不同（战役侧逐点 texCoord 累计、
 * perp 法线、fadeOut/proximity 状态机、尾点宽度 1/4 的基准不同）、淡入淡出公式
 * 不同（战役侧基于 elapsed/elapsedWhenFadeOut 的双段线性，战斗侧基于
 * progress/fadeWindow），无参数化共用价值。
 */
public final class CampaignContrailBatchHelper {
    private static final int   MAX_VERTICES           = 262_144;
    private static final float V_MIN                  = 0.01f;
    private static final float V_MAX                  = 0.99f;
    private static final int   GL_SRC_ALPHA           = 770;
    private static final int   GL_ONE                 = 1;
    private static final int   GL_ONE_MINUS_SRC_ALPHA = 771;
    /** 原版 FULL_BRIGHTNESS_LENGTH：proximity 淡出的距离归一化长度。 */
    private static final float FULL_BRIGHTNESS_LENGTH = 50.0f;

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
     * 批次连接点需要的「上一组末顶点」（恒为右顶点，V=V_MAX）：同混合模式尾迹
     * 拼接进同一 strip 时重复写入两次，形成零面积退化四边形。
     */
    private static float lastU;
    private static float lastX;
    private static float lastY;
    private static byte  lastR;
    private static byte  lastG;
    private static byte  lastB;
    private static byte  lastA;

    private CampaignContrailBatchHelper() {
    }

    /**
     * 整次尾迹渲染：client attrib 压栈一次、三个 client array 使能一次，随后按
     * {@code contrails} 的遍历顺序编码。混合模式变化处结束当前批次（flush 一次
     * draw）并切换混合；同混合模式的相邻尾迹合并为同一条 strip。
     *
     * @param contrails  原版 {@code ContrailEngineV2.contrails}
     * @param alphaMult  全局透明度缩放（原版 render(float) 的 var1）
     */
    public static void renderContrails(Map<Object, ContrailEngineV2.Contrail> contrails, float alphaMult) {
        if (contrails.isEmpty()) {
            return;
        }

        try {
            renderAllContrails(contrails, alphaMult);
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to render ContrailEngineV2 contrails", e);
        }
    }

    private static void renderAllContrails(Map<Object, ContrailEngineV2.Contrail> contrails, float alphaMult) {
        beginStrip();
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

            // 原版 var4：最近 fadeOut 点，声明在 render 方法级，跨尾迹存续。
            ContrailEngineV2.ContrailPoint fadeSource = null;
            Object activeBlend = new Object();
            boolean textureBound = false;
            for (ContrailEngineV2.Contrail contrail : contrails.values()) {
                // 混合模式变化 = 批次边界：先结束当前 strip，再切换混合。
                // 不可绘制尾迹同样推进 blend 状态（原版对每条尾迹无条件 glBlendFunc）。
                EngineSlot.BlendMode blend = contrail.blendMode;
                if (activeBlend != blend) {
                    flushStrip();
                    applyBlendMode(blend);
                    activeBlend = blend;
                }

                if (contrail.points.size() > 1) {
                    // 原版 var3 标志：整次 render 只在第一条可绘制尾迹处 bind 一次
                    // （全部尾迹共享同一纹理实例，后续 tex 不会被绑定）。
                    if (!textureBound) {
                        contrail.tex.bind();
                        textureBound = true;
                    }
                    fadeSource = encodeContrail(contrail, alphaMult, fadeSource);
                } else {
                    // 原版 else 分支：不可绘制尾迹的全部点 maxBrightness 清零。
                    for (ContrailEngineV2.ContrailPoint point : contrail.points) {
                        point.maxBrightness = 0.0f;
                    }
                }
            }

            flushStrip();
            restoreCurrentColor();
        } finally {
            GL11.glPopClientAttrib();
        }
    }

    /**
     * 把一条尾迹的全部点与尾点编码进当前批次缓冲，并执行原版的渲染期状态改写
     * （fadeOut 检测、proximity 写回）。批次非空时先补连接点（重复上一组末顶点
     * 两次，零面积退化四边形不产生碎片）。
     *
     * @param fadeSource 截至上一条尾迹的最近 fadeOut 点（原版 var4，可为 null）
     * @return 本条尾迹处理后的最近 fadeOut 点，供下一条尾迹继续使用
     */
    static ContrailEngineV2.ContrailPoint encodeContrail(ContrailEngineV2.Contrail contrail,
                                                         float alphaMult,
                                                         ContrailEngineV2.ContrailPoint fadeSource) {
        Color color = contrail.color;
        int red = color.getRed();
        int green = color.getGreen();
        int blue = color.getBlue();
        int baseAlpha = color.getAlpha();

        emitJoinConnector();

        List<ContrailEngineV2.ContrailPoint> points = contrail.points;
        int size = points.size();
        ContrailEngineV2.ContrailPoint prev = null;
        for (int i = 0; i < size; i++) {
            ContrailEngineV2.ContrailPoint point = points.get(i);

            // ---- 老化零亮度点简化路径（B1）----
            // 条件保守地限定为「fadeOut 已标记且 maxBrightness == 0」的点（encode
            // 期间 maxBrightness 无人改写，恒 0 成立）。逐项复核原版副作用：
            // - 顶点 alpha：baseAlpha × maxBrightness(0) × brightness 恒为 ±0/NaN，
            //   (int) 转换后恒 0（brightness 为 NaN 时 (int) NaN == 0 同样成立），
            //   与亮度双段线性公式的具体取值无关——除法整体跳过，直接写 alpha=0；
            // - 相交/折返检测：唯一副作用是 markFadeOut，对已 fadeOut 点恒为 no-op
            //   （origMax/elapsedWhenFadeOut 快照只在首次标记时写入），跳过不丢状态；
            // - fadeSource 更新：fadeOut 点即成为自己的参照，必须照常发生；更新后
            //   fadeSource == 本点 ⇒ proximity 的 dist 恒 0、fadeRatio 恒 1
            //   （origMax>0 时 maxBrightness 为 0；origMax<=0 时直接取 1）⇒
            //   mult 恒为精确 0.0f，sqrt 与除法跳过；lastProximityMult 写回按原版
            //   min 钳制代入 mult=0 逐字保留（含 -0.0/NaN 的位级行为）。
            if (point.fadeOut && point.maxBrightness == 0.0f) {
                fadeSource = point;
                float mult = 0.0f;
                if (mult > point.lastProximityMult) {
                    mult = point.lastProximityMult;
                }
                point.lastProximityMult = mult;

                float halfWidth = point.width / 2.0f;
                addPair(
                        red, green, blue, 0,
                        point.texCoord,
                        point.point.x - point.perp.x * halfWidth,
                        point.point.y - point.perp.y * halfWidth,
                        point.point.x + point.perp.x * halfWidth,
                        point.point.y + point.perp.y * halfWidth
                );
                prev = point;
                continue;
            }

            ContrailEngineV2.ContrailPoint next = i + 1 < size ? points.get(i + 1) : null;

            // ---- 亮度（原版 var12）：双段线性 + 钳制 + 全局缩放 ----
            float brightness = 0.0f;
            float fadeWindow = 0.1f;
            float elapsed = point.elapsed;
            if (elapsed > point.elapsedWhenFadeOut) {
                elapsed = point.elapsedWhenFadeOut;
            }
            if (fadeWindow > point.duration / 2.0f) {
                fadeWindow = point.duration / 2.0f;
            }
            if (elapsed < fadeWindow) {
                brightness = elapsed / fadeWindow;
            } else {
                brightness = (point.duration - elapsed) / (point.duration - fadeWindow);
            }
            if (brightness > 1.0f) {
                brightness = 1.0f;
            }
            brightness *= alphaMult;

            // ---- fadeOut 检测（原版在亮度计算之后、首点清零之前）----
            if (next != null) {
                // 当前点展开段：point ± perp × (width/2×1.5)，端点惰性展开——
                // 包围圆拒绝命中时整段数学与 segmentsIntersect 一并跳过（见下方注释）。
                float aMinX = 0.0f;
                float aMinY = 0.0f;
                float aMaxX = 0.0f;
                float aMaxY = 0.0f;
                boolean curExpanded = false;

                // 包围圆拒绝：展开段端点 = point ± perp × (width/2×1.5)，perp 恒为
                // 单位向量（ContrailEngineV2.addPoint 经 Utils.normalise +
                // getPerpendicular 生成，反序列化截断只缩不扩），故展开段必落在
                // 以 point 为圆心、width×0.75 为半径的圆内。dist² > ((wA+wB)×0.75)²
                // 时两圆不相交 ⇒ 展开段必不相交 ⇒ segmentsIntersect 结论恒为 false，
                // markFadeOut 必不触发——与展开检测的原路径逐点结论一致。
                float ndx = next.point.x - point.point.x;
                float ndy = next.point.y - point.point.y;
                float nextRadSum = (point.width + next.width) * 0.75f;
                boolean nextIntersects = false;
                if (ndx * ndx + ndy * ndy <= nextRadSum * nextRadSum) {
                    float halfExt = point.width / 2.0f * 1.5f;
                    aMinX = point.point.x - point.perp.x * halfExt;
                    aMinY = point.point.y - point.perp.y * halfExt;
                    aMaxX = point.point.x + point.perp.x * halfExt;
                    aMaxY = point.point.y + point.perp.y * halfExt;
                    curExpanded = true;

                    float nextExt = next.width / 2.0f * 1.5f;
                    float bMinX = next.point.x - next.perp.x * nextExt;
                    float bMinY = next.point.y - next.perp.y * nextExt;
                    float bMaxX = next.point.x + next.perp.x * nextExt;
                    float bMaxY = next.point.y + next.perp.y * nextExt;

                    nextIntersects = segmentsIntersect(aMinX, aMinY, aMaxX, aMaxY, bMinX, bMinY, bMaxX, bMaxY);
                }
                if (nextIntersects) {
                    markFadeOut(point);
                } else if (prev != null) {
                    // 与后点同理的包围圆拒绝：拒绝时 segmentsIntersect 恒为 false。
                    float pdx = prev.point.x - point.point.x;
                    float pdy = prev.point.y - point.point.y;
                    float prevRadSum = (point.width + prev.width) * 0.75f;
                    if (pdx * pdx + pdy * pdy <= prevRadSum * prevRadSum) {
                        if (!curExpanded) {
                            float halfExt = point.width / 2.0f * 1.5f;
                            aMinX = point.point.x - point.perp.x * halfExt;
                            aMinY = point.point.y - point.perp.y * halfExt;
                            aMaxX = point.point.x + point.perp.x * halfExt;
                            aMaxY = point.point.y + point.perp.y * halfExt;
                        }

                        float prevExt = prev.width / 2.0f * 1.5f;
                        float cMinX = prev.point.x - prev.perp.x * prevExt;
                        float cMinY = prev.point.y - prev.perp.y * prevExt;
                        float cMaxX = prev.point.x + prev.perp.x * prevExt;
                        float cMaxY = prev.point.y + prev.perp.y * prevExt;

                        if (segmentsIntersect(aMinX, aMinY, aMaxX, aMaxY, cMinX, cMinY, cMaxX, cMaxY)) {
                            markFadeOut(point);
                        }
                    }

                    // 前点折返判定：prev/next 相对当前点同向（dot > 0）即折返。
                    // 守卫用检测前的亮度（原版 var12 此时未经首点清零与 proximity）。
                    if (brightness > 0.0f) {
                        float toPrevX = prev.point.x - point.point.x;
                        float toPrevY = prev.point.y - point.point.y;
                        float toNextX = next.point.x - point.point.x;
                        float toNextY = next.point.y - point.point.y;
                        if (toPrevX * toNextX + toPrevY * toNextY > 0.0f) {
                            markFadeOut(point);
                        }
                    }
                }
            }

            // 原版：首点（var9 == 1.0F）亮度强制为 0。
            if (i == 0) {
                brightness = 0.0f;
            }

            // 原版顺序：先以本点更新 fadeSource（fadeOut 点即成为自己的参照），
            // 再做 proximity 衰减。
            if (point.fadeOut) {
                fadeSource = point;
            }
            if (fadeSource != null) {
                float dx = point.point.x - fadeSource.point.x;
                float dy = point.point.y - fadeSource.point.y;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float fadeRatio = 1.0f;
                if (fadeSource.origMax > 0.0f) {
                    fadeRatio = 1.0f - fadeSource.maxBrightness / fadeSource.origMax;
                }
                float mult = 1.0f - fadeRatio + fadeRatio * Math.min(1.0f, dist / FULL_BRIGHTNESS_LENGTH);
                if (mult > point.lastProximityMult) {
                    mult = point.lastProximityMult;
                }
                point.lastProximityMult = mult;
                brightness *= mult;
            }

            // 原版 RenderStateUtils.setGlColor(color, (int)(alpha*maxBrightness*var12))
            // 的 alpha 字节：(byte)(int)(...) 直转，不钳制（与原版一致）。
            int alpha = (int) (baseAlpha * point.maxBrightness * brightness);
            float halfWidth = point.width / 2.0f;

            addPair(
                    red, green, blue, alpha,
                    point.texCoord,
                    point.point.x - point.perp.x * halfWidth,
                    point.point.y - point.perp.y * halfWidth,
                    point.point.x + point.perp.x * halfWidth,
                    point.point.y + point.perp.y * halfWidth
            );
            prev = point;
        }

        // ---- 尾点（原版 var26 恒为末点：循环无跳过地处理全部点）----
        ContrailEngineV2.ContrailPoint last = points.get(size - 1);
        Vector2f lastPoint = contrail.lastPoint;
        if (lastPoint != null) {
            float quarterWidth = last.width / 4.0f;
            addPair(
                    red, green, blue, 0,
                    last.texCoord,
                    lastPoint.x - last.perp.x * quarterWidth,
                    lastPoint.y - last.perp.y * quarterWidth,
                    lastPoint.x + last.perp.x * quarterWidth,
                    lastPoint.y + last.perp.y * quarterWidth
            );
        }
        return fadeSource;
    }

    /**
     * 原版 fadeOut 标记：首次标记时快照 origMax 与 elapsedWhenFadeOut。
     */
    private static void markFadeOut(ContrailEngineV2.ContrailPoint point) {
        if (!point.fadeOut) {
            point.origMax = point.maxBrightness;
            point.elapsedWhenFadeOut = point.elapsed;
        }
        point.fadeOut = true;
    }

    /**
     * {@code VectorMathUtils.segmentIntersection} 的无分配等价判定：调用方只需要
     * null/非 null 结论，表达式与判定顺序逐行照搬原版，结论位级一致。
     */
    static boolean segmentsIntersect(float ax, float ay, float bx, float by,
                                     float cx, float cy, float dx, float dy) {
        float denom = (dy - cy) * (bx - ax) - (dx - cx) * (by - ay);
        float ua = (dx - cx) * (ay - cy) - (dy - cy) * (ax - cx);
        float ub = (bx - ax) * (ay - cy) - (by - ay) * (ax - cx);
        if (denom != 0.0f || ua == 0.0f && ub == 0.0f) {
            if (denom == 0.0f && ua == 0.0f && ub == 0.0f) {
                // 共线：c/d 是否落入 ab 的包围盒
                float minX;
                float maxX;
                if (ax < bx) {
                    minX = ax;
                    maxX = bx;
                } else {
                    minX = bx;
                    maxX = ax;
                }
                float minY;
                float maxY;
                if (ay < by) {
                    minY = ay;
                    maxY = by;
                } else {
                    minY = by;
                    maxY = ay;
                }
                if (cx >= minX && cx <= maxX && cy >= minY && cy <= maxY) {
                    return true;
                }
                return dx >= minX && dx <= maxX && dy >= minY && dy <= maxY;
            }
            float t = ua / denom;
            float u = ub / denom;
            return t >= 0.0f && t <= 1.0f && u >= 0.0f && u <= 1.0f;
        }
        return false;
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
     * 批次非空时补连接点：重复上一组末顶点（右顶点）两次。批次为空（本尾迹是
     * 当前批次第一条）时无连接点。
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
     * 整次渲染结束时的 current color 恢复：设为本批次最后一段的末顶点颜色
     * （原版逐条绘制结束时 current color 同样为最后一条尾迹末顶点颜色——尾点
     * alpha 为 0）。无任何绘制时不调用 glColor4ub，与原版一致。
     */
    private static void restoreCurrentColor() {
        if (!hasFlushedStrip) {
            return;
        }
        GL11.glColor4ub(finalR, finalG, finalB, finalA);
    }

    private static void applyBlendMode(EngineSlot.BlendMode blendMode) {
        if (blendMode == EngineSlot.BlendMode.GLOW) {
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        } else {
            GL11.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
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
