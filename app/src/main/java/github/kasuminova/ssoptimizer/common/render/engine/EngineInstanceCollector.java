package github.kasuminova.ssoptimizer.common.render.engine;

import java.awt.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 引擎渲染实例收集器（纯 CPU 计算，不触碰 GL）。
 * <p>
 * 逐行移植原版 {@code com.fs.starfarer.combat.entities.Engine.render(float)} 与
 * {@code renderFighter(float)} 的参数公式：对一艘船的全部引擎槽执行「收集」，
 * 产出按 (阶段 × 纹理ID) 分组的实例列表，供合批渲染一次性 flush。
 * <p>
 * 与原版的关键语义约定：
 * <ul>
 *   <li>alpha 一律经 {@code (int)} 截断后 {@code (byte)} 取低 8 位（等价 glColor4ub 入参），
 *       不做 clamp —— 与原版 {@code RenderStateUtils.setGlColor(color, int)} 完全一致；</li>
 *   <li>maxSpread 为 0 时不做除零保护，NaN 随顶点传播（原版立即模式同样产出 NaN 顶点，GPU 丢弃）；</li>
 *   <li>glow 的火焰强度项使用重置后的 1.0F（原版 var9/var7 在 glow 前被重置为 1.0F）；</li>
 *   <li>全部图元为 additive 混合（glBlendFunc(770,1)）且无深度测试，跨槽重排严格等价。</li>
 * </ul>
 */
public final class EngineInstanceCollector {
    /** 原版角度转弧度系数（与 glRotatef 角度制输入对应）。 */
    private static final float DEG_TO_RAD = 0.017453292519943295769f;
    /** 原版纹素内缩（var4 = 0.01F）。 */
    public static final float TEX_PAD = 0.01f;
    public static final float TEX_MIN = 0.01f;
    public static final float TEX_MAX = 0.99f;

    /** VBO 模式的单顶点字节数：x,y,u,v（float×4）+ r,g,b,a（ubyte×4）。 */
    public static final int VERTEX_BYTES = 20;

    // ---------------------------------------------------------------------
    // native flush 扁平化布局（与 ssoptimizer_engine_batch.cpp 的结构体一一对应，
    // 修改任一字段必须同步修改 C++ 侧；全部小端/nativeOrder 写入）
    // ---------------------------------------------------------------------

    /** 阶段：尾焰条带（6 顶点/实例，12 索引/实例）。 */
    public static final int STAGE_STRIP = 0;
    /** 阶段：火焰核心（4 顶点/实例，6 索引/实例）。 */
    public static final int STAGE_CORE  = 1;
    /** 阶段：辉光精灵（4 顶点/实例，6 索引/实例）。 */
    public static final int STAGE_GLOW  = 2;

    /** 绘制命令记录字节数：stage / textureId / instanceCount / dataOffset 各 4 字节。 */
    public static final int COMMAND_BYTES = 16;
    /** 条带实例字节数：14 float + r/g/b/alphaStart/alphaMid 5 字节 + 3 字节对齐填充。 */
    public static final int STRIP_INSTANCE_BYTES = 64;
    /** 核心实例字节数：7 float + rgba 4 字节。 */
    public static final int CORE_INSTANCE_BYTES = 32;
    /** 辉光实例字节数：10 float + rgba 4 字节。 */
    public static final int GLOW_INSTANCE_BYTES = 44;

    private EngineInstanceCollector() {
    }

    /** 渲染阶段：决定纹理分组与 flush 顺序。 */
    public enum Stage {
        /** 尾焰条带（glow 纹理，按 primary/secondary 纹理再分组）。 */
        FLAME_STRIP,
        /** 火焰核心（flame 纹理）。 */
        FLAME_CORE,
        /** 辉光精灵（hit_glow 纹理，每槽外圈 + 白芯两个 quad）。 */
        GLOW_SPRITE
    }

    /** 合批分组键：阶段 × 纹理 ID（每期纹理可被船包按船覆盖，纹理必须参与分组）。 */
    public record GroupKey(Stage stage, int textureId) {
    }

    /**
     * 帧级输入：对应原版 {@code render(float)} 方法开头计算的常量与每期纹理。
     * 所有字段为「原始值」，公式变换（omega 角速度改写、glowBrightness 开方等）在收集器内完成。
     */
    public record FrameInput(
            float alphaScale,
            float angularVelocity,
            boolean omegaMode,
            boolean withSpread,
            boolean fighter,
            boolean missile,
            boolean boostedFlameMode,
            float primaryBrightness,
            float secondaryBrightness,
            boolean shiftersShifted,
            float lengthShiftCurr,
            float widthShiftCurr,
            float glowShiftCurr,
            int stripPrimaryTextureId,
            int stripSecondaryTextureId,
            int flameTextureId,
            int glowSpriteTextureId,
            float glowSpriteUScale,
            float glowSpriteVScale) {
    }

    /**
     * 槽位级输入：对应原版 for 循环体内从 EngineSlot / EngineState 读取的值。
     * {@code flameLevel} 为系统激活缩放后的值（原版 var9），{@code adjustedLevel} 为 var10；
     * 颜色均为 colorShifter 混合后的最终色；{@code glowColor} 为 null 时表示无交替色（用 {@code color}）。
     */
    public record SlotInput(
            float flameLevel,
            float adjustedLevel,
            float texU,
            float coreRotation,
            float spread,
            float posX,
            float posY,
            float midArcAngle,
            float maxSpread,
            float slotLength,
            float slotWidth,
            Color color,
            Color glowColor,
            float glowSizeMult,
            boolean primaryGlowType) {
    }

    /** 尾焰条带实例：一次 quad-strip pass 的全部参数（字段与原版矩阵栈操作一一对应）。 */
    public record StripInstance(
            float posX, float posY,
            float angle,
            float rotation1,
            float rotation2,
            float translateX,
            float scaleX,
            float scaleY,
            float halfWidth,
            float innerLength,
            float stripLength,
            float texU,
            float texSpan,
            float texAdvance,
            int red, int green, int blue,
            int alphaStart,
            int alphaMid,
            int textureId) {
    }

    /** 火焰核心实例：单个 4 顶点 quad-strip。 */
    public record CoreInstance(
            float posX, float posY,
            float angle,
            float coreRotation,
            float omegaRotation,
            float stripLength,
            float halfWidth,
            int red, int green, int blue,
            int alpha,
            int textureId) {
    }

    /** 辉光精灵实例：单个 quad（原版 Sprite.renderAtCenter 展开）。 */
    public record GlowInstance(
            float posX, float posY,
            float angle,
            float coreRotation,
            float scaleX,
            float size,
            float texU0, float texV0,
            float texU1, float texV1,
            int red, int green, int blue,
            int alpha,
            int textureId) {
    }

    /** 分组公共视图：native 扁平化统计实例数用。 */
    public interface EngineGroup {
        int textureId();

        int instanceCount();
    }

    /** 尾焰条带分组（同纹理一批绘制）。 */
    public record StripGroup(int textureId, List<StripInstance> instances) implements EngineGroup {
        public GroupKey key() {
            return new GroupKey(Stage.FLAME_STRIP, textureId);
        }

        @Override
        public int instanceCount() {
            return instances.size();
        }
    }

    /** 火焰核心分组。 */
    public record CoreGroup(int textureId, List<CoreInstance> instances) implements EngineGroup {
        public GroupKey key() {
            return new GroupKey(Stage.FLAME_CORE, textureId);
        }

        @Override
        public int instanceCount() {
            return instances.size();
        }
    }

    /** 辉光精灵分组。 */
    public record GlowGroup(int textureId, List<GlowInstance> instances) implements EngineGroup {
        public GroupKey key() {
            return new GroupKey(Stage.GLOW_SPRITE, textureId);
        }

        @Override
        public int instanceCount() {
            return instances.size();
        }
    }

    /**
     * 合批输出：组内保持收集顺序，组间按纹理首次出现排序；flush 顺序为 strips → cores → glows
     * （additive 混合交换律成立，与原版逐槽交错绘制等价）。
     */
    public static final class CollectedBatch {
        public final List<StripGroup> strips = new ArrayList<>(2);
        public final List<CoreGroup>  cores  = new ArrayList<>(1);
        public final List<GlowGroup>  glows  = new ArrayList<>(1);

        public boolean isEmpty() {
            return strips.isEmpty() && cores.isEmpty() && glows.isEmpty();
        }

        void add(StripInstance instance) {
            for (StripGroup group : strips) {
                if (group.textureId() == instance.textureId()) {
                    group.instances().add(instance);
                    return;
                }
            }
            List<StripInstance> list = new ArrayList<>();
            list.add(instance);
            strips.add(new StripGroup(instance.textureId(), list));
        }

        void add(CoreInstance instance) {
            for (CoreGroup group : cores) {
                if (group.textureId() == instance.textureId()) {
                    group.instances().add(instance);
                    return;
                }
            }
            List<CoreInstance> list = new ArrayList<>();
            list.add(instance);
            cores.add(new CoreGroup(instance.textureId(), list));
        }

        void add(GlowInstance instance) {
            for (GlowGroup group : glows) {
                if (group.textureId() == instance.textureId()) {
                    group.instances().add(instance);
                    return;
                }
            }
            List<GlowInstance> list = new ArrayList<>();
            list.add(instance);
            glows.add(new GlowGroup(instance.textureId(), list));
        }
    }

    /**
     * 收集一艘船全部引擎槽的渲染实例。
     *
     * @param frame 帧级输入
     * @param slots 槽位输入（已过滤 flameLevel==0 与系统激活禁渲染的槽）
     * @param out   输出容器
     */
    public static void collect(FrameInput frame, List<SlotInput> slots, CollectedBatch out) {
        // 原版：var5 = -(angularVelocity * 0.15F)；omega 分支整体改写（var5 *= 2.5F 为死赋值，不复刻）
        float angularRotation = -(frame.angularVelocity() * 0.15f);
        if (frame.omegaMode()) {
            float sign = Math.signum(-frame.angularVelocity());
            float ratio = Math.min(1.0f, Math.abs(frame.angularVelocity()) / 120.0f);
            ratio *= ratio;
            angularRotation = sign * ratio * 10.0f;
        }

        // 原版：var53 = secondaryFader.getBrightness() 开方后乘 0.75（每帧常量，循环内重复计算）
        float glowBrightness = frame.secondaryBrightness();
        if (glowBrightness > 0.0f) {
            glowBrightness = (float) Math.sqrt(glowBrightness);
        }
        glowBrightness *= 0.75f;

        for (SlotInput slot : slots) {
            if (frame.fighter()) {
                collectFighterSlot(frame, slot, angularRotation, glowBrightness, out);
            } else {
                collectShipSlot(frame, slot, angularRotation, glowBrightness, out);
            }
        }
    }

    /** 舰船（非战机）槽位收集：逐行对应原版 render(float) 循环体。 */
    private static void collectShipSlot(FrameInput frame, SlotInput slot,
                                        float angularRotation, float glowBrightness,
                                        CollectedBatch out) {
        float flameLevel = slot.flameLevel();       // var9
        float adjustedLevel = slot.adjustedLevel(); // var10
        float spread = slot.spread();               // var13
        float maxSpread = slot.maxSpread();         // var14
        Color color = slot.color();                 // var15（已混合 colorShifter）

        float edgeAlpha = flameLevel / 0.4f;        // var16
        if (edgeAlpha > 1.0f) {
            edgeAlpha = 1.0f;
        }

        // var45 = widthFactor，var47 = lengthFactor（boostedFlameMode 双分支逐行照搬）
        float widthFactor;
        float lengthFactor;
        if (frame.boostedFlameMode()) {
            widthFactor = Math.min(adjustedLevel, 0.8f) / 0.8f;
            widthFactor *= widthFactor;
            if (widthFactor < 0.45000005f) {
                widthFactor = 0.45000005f;
            }
            lengthFactor = Math.max(0.0f, adjustedLevel - 0.8f) / 0.19999999f;
            lengthFactor *= lengthFactor;
        } else {
            widthFactor = Math.max(0.09f, adjustedLevel - 0.8f) / 0.19999999f;
            lengthFactor = Math.max(0.0f, adjustedLevel - 0.8f) / 0.19999999f;
        }

        // 原版无 maxSpread==0 保护：0/0 产生 NaN 并随顶点传播（与立即模式行为一致）
        if (frame.omegaMode()) {
            widthFactor *= (spread * 2.0f + maxSpread) / maxSpread;
        } else {
            widthFactor *= (spread + maxSpread) / maxSpread;
        }

        float primaryBrightness = frame.primaryBrightness(); // var19
        float length = slot.slotLength() + slot.slotLength() * 0.25f * primaryBrightness; // var20
        length += slot.slotLength() * frame.lengthShiftCurr();
        float width = slot.slotWidth(); // var21
        width += slot.slotWidth() * frame.widthShiftCurr();

        float stripLength = length * (0.2f + lengthFactor * 0.8f);          // var22
        float innerWidth = slot.slotWidth() * (0.1f + widthFactor * 0.9f);  // var23（注意：基于槽宽原值）
        float stripWidth = width * (0.1f + widthFactor * 0.9f);             // var24

        if (!frame.withSpread()) {
            float spreadRatio = spread / 90.0f; // var25
            if (spreadRatio > 1.0f) {
                spreadRatio = 1.0f;
            }
            if (spreadRatio < 0.0f) {
                spreadRatio = 0.0f;
            }
            spread = 0.0f;
            stripLength *= 1.0f - spreadRatio * 0.5f;
            stripWidth *= 1.0f + spreadRatio * 0.25f;
        }

        float spreadRotation = (1.0f - stripLength / length) * spread; // var50
        float textureAdvance = flameLevel * 1.0f;                      // var26
        float passCount = 6.0f;                                        // var27
        if (frame.omegaMode()) {
            passCount = 1.0f;
            spreadRotation = 0.0f;
        }

        float texU = slot.texU();                          // var28
        float innerLength = Math.min(innerWidth / 2.0f, stripLength / 4.0f); // var29
        float texSpan = innerLength / stripLength;         // var30

        int stripTextureId = slot.primaryGlowType()
                ? frame.stripPrimaryTextureId()
                : frame.stripSecondaryTextureId();
        int layerCount = frame.omegaMode() ? 2 : 1;        // var31
        int colorAlpha = color.getAlpha();

        for (int layer = 0; layer < layerCount; layer++) {
            for (int pass = 0; pass < (int) passCount; pass++) {
                float passF = pass; // 原版循环变量 var33 为 float
                float rotation1 = (passCount - passF - 1.0f) / passCount * angularRotation; // var34
                if (frame.omegaMode()) {
                    rotation1 = angularRotation;
                }
                float direction = 1.0f; // var35
                if (passF % 2.0f == 0.0f) {
                    direction = -1.0f;
                }
                float phase = (passF + 1.0f) / 2.0f; // var36
                float rotation2 = (passCount / 2.0f - phase - 1.0f) / (passCount / 2.0f)
                        * direction * 2.0f * spreadRotation;
                float translateX = (passCount - passF - 1.0f) * innerLength / (passCount * 2.0f);
                float scaleX = 0.5f + 0.5f * (passF + 1.0f) / passCount;
                float scaleY = 1.0f * (passCount - passF) / passCount;

                // 原版 setGlColor(color, (int)(var33 * 5.0F * alpha / 255.0F * f * var16))
                int alphaStart = glColorByte(passF * 5.0f * colorAlpha / 255.0f
                        * frame.alphaScale() * edgeAlpha);
                // 原版 setGlColor(color, (int)(100.0F * 1.0F * alpha / 255.0F * f * var16))
                int alphaMid = glColorByte(100.0f * 1.0f * colorAlpha / 255.0f
                        * frame.alphaScale() * edgeAlpha);

                out.add(new StripInstance(
                        slot.posX(), slot.posY(), slot.midArcAngle(),
                        rotation1, rotation2, translateX, scaleX, scaleY,
                        stripWidth * 0.5f, innerLength, stripLength,
                        texU, texSpan, textureAdvance,
                        color.getRed(), color.getGreen(), color.getBlue(),
                        alphaStart, alphaMid, stripTextureId));

                texU += 1.0f / passCount;
            }
        }

        // 火焰核心：原版 var55 = omega 时取 angularRotation，否则 0
        float omegaRotation = frame.omegaMode() ? angularRotation : 0.0f;
        // 原版 setGlColor(color, (int)(var9 * 50.0F * alpha / 255.0F * f * var16))
        int coreAlpha = glColorByte(flameLevel * 50.0f * colorAlpha / 255.0f
                * frame.alphaScale() * edgeAlpha);
        out.add(new CoreInstance(
                slot.posX(), slot.posY(), slot.midArcAngle(),
                slot.coreRotation(), omegaRotation,
                stripLength, stripWidth * 0.5f,
                color.getRed(), color.getGreen(), color.getBlue(),
                coreAlpha, frame.flameTextureId()));

        // 辉光：原版在 glow 前将 var9 重置为 1.0F，火焰强度项恒为 1.0F - 0.4F；
        // 舰船路径 glow 处于 R(coreRotation)·S(0.9,1) 矩阵内（核心 pass 的 rotate/scale 未弹栈）
        collectGlow(frame, slot, glowBrightness, primaryBrightness, edgeAlpha,
                spread, maxSpread, innerWidth, stripWidth,
                slot.coreRotation(), 0.9f, out);
    }

    /** 战机槽位收集：逐行对应原版 renderFighter(float) 循环体。 */
    private static void collectFighterSlot(FrameInput frame, SlotInput slot,
                                           float angularRotation, float glowBrightness,
                                           CollectedBatch out) {
        float flameLevel = slot.flameLevel();       // var7
        float adjustedLevel = slot.adjustedLevel(); // var8
        float spread = slot.spread();               // var11
        float maxSpread = slot.maxSpread();         // var12
        Color color = slot.color();                 // var13

        float edgeAlpha = flameLevel / 0.4f;        // var14
        if (edgeAlpha > 1.0f) {
            edgeAlpha = 1.0f;
        }

        float widthFactor; // var35
        float lengthFactor; // var37
        if (frame.boostedFlameMode()) {
            widthFactor = Math.min(adjustedLevel, 0.8f) / 0.8f;
            widthFactor *= widthFactor;
            if (widthFactor < 0.45000005f) {
                widthFactor = 0.45000005f;
            }
            lengthFactor = Math.max(0.0f, adjustedLevel - 0.8f) / 0.19999999f;
            lengthFactor *= lengthFactor;
        } else {
            widthFactor = Math.max(0.09f, adjustedLevel - 0.8f) / 0.19999999f;
            lengthFactor = Math.max(0.0f, adjustedLevel - 0.8f) / 0.19999999f;
        }
        // 战机路径无 omega 分支、无 withSpread 重置
        widthFactor *= (spread + maxSpread) / maxSpread;

        float primaryBrightness = frame.primaryBrightness(); // var17
        float length = slot.slotLength() + slot.slotLength() * 0.25f * primaryBrightness; // var18
        length += slot.slotLength() * frame.lengthShiftCurr();
        float width = slot.slotWidth(); // var19
        width += slot.slotWidth() * frame.widthShiftCurr();

        float stripLength = length * (0.2f + lengthFactor * 0.8f);          // var20
        float innerWidth = slot.slotWidth() * (0.1f + widthFactor * 0.9f);  // var21
        float stripWidth = width * (0.1f + widthFactor * 0.9f);             // var22

        float textureAdvance = flameLevel * 1.0f; // var23
        float passCount = 3.0f;                   // var24
        float texU = slot.texU();                 // var25
        float innerLength = Math.min(innerWidth / 2.0f, stripLength / 4.0f); // var26
        float texSpan = innerLength / stripLength; // var27

        int stripTextureId = slot.primaryGlowType()
                ? frame.stripPrimaryTextureId()
                : frame.stripSecondaryTextureId();
        int colorAlpha = color.getAlpha();

        for (int pass = 0; pass < (int) passCount; pass++) {
            float passF = pass; // 原版循环变量 var28 为 float
            float rotation1 = (passCount - passF - 1.0f) / passCount * angularRotation;
            float translateX = (passCount - passF - 1.0f) * innerLength / (passCount * 2.0f);
            float scaleX = 0.5f + 0.5f * (passF + 1.0f) / passCount;
            float scaleY = 1.0f * (passCount - passF) / passCount;

            // 原版 setGlColor(color, (int)(var28 * 5.0F * alpha / 255.0F * f * var14))
            int alphaStart = glColorByte(passF * 5.0f * colorAlpha / 255.0f
                    * frame.alphaScale() * edgeAlpha);
            // 原版 setGlColor(color, (int)(255.0F * 1.0F * alpha / 255.0F * f * var14))
            int alphaMid = glColorByte(255.0f * 1.0f * colorAlpha / 255.0f
                    * frame.alphaScale() * edgeAlpha);

            out.add(new StripInstance(
                    slot.posX(), slot.posY(), slot.midArcAngle(),
                    rotation1, 0.0f, translateX, scaleX, scaleY,
                    stripWidth * 0.5f, innerLength, stripLength,
                    texU, texSpan, textureAdvance,
                    color.getRed(), color.getGreen(), color.getBlue(),
                    alphaStart, alphaMid, stripTextureId));

            texU += 1.0f / passCount;
        }

        // 战机路径无火焰核心 pass

        // 辉光：原版在 glow 前将 var7 重置为 1.0F；
        // 战机路径没有核心 pass 的 R(coreRotation)·S(0.9,1)，glow 仅处于 T(pos)·R(angle) 矩阵内
        collectGlow(frame, slot, glowBrightness, primaryBrightness, edgeAlpha,
                spread, maxSpread, innerWidth, stripWidth,
                0.0f, 1.0f, out);
    }

    /** 辉光精灵收集：舰船 / 战机共用（原版两路径公式一致，仅 glowSize 的 missile/fighter 系数不同）。
     *
     * @param glowRotation glow  quad 附加旋转（舰船为 coreRotation，战机为 0）
     * @param glowScaleX   glow quad 的 x 缩放（舰船为 0.9，战机为 1.0）
     */
    private static void collectGlow(FrameInput frame, SlotInput slot,
                                    float glowBrightness, float primaryBrightness,
                                    float edgeAlpha,
                                    float spread, float maxSpread,
                                    float innerWidth, float stripWidth,
                                    float glowRotation, float glowScaleX,
                                    CollectedBatch out) {
        if (frame.glowSpriteTextureId() < 0) {
            // 原版 Sprite.render 在 texture == null 时不绘制任何内容
            return;
        }

        float glowSize; // var57 / var44
        if (!frame.shiftersShifted()) {
            glowSize = stripWidth * (2.0f + 1.0f * primaryBrightness);
        } else {
            float extra = (stripWidth - innerWidth * frame.widthShiftCurr()) * 2.0f; // var58 / var45
            glowSize = extra + extra * 0.5f * primaryBrightness;
            glowSize += extra * frame.glowShiftCurr();
        }

        float glowAlphaBase = Math.max(primaryBrightness * 0.25f, spread / maxSpread * 0.5f); // var59 / var46
        glowAlphaBase = Math.max(glowAlphaBase, glowBrightness);
        // 原版此处使用重置为 1.0F 的 flameLevel：Math.max(var59, 1.0F - 0.4F)
        float glowAlpha = Math.max(glowAlphaBase, 1.0f - 0.4f) * 0.75f * frame.alphaScale();
        glowAlpha = Math.min(edgeAlpha, glowAlpha);

        if (frame.missile()) {
            glowSize *= 2.0f;
        } else if (frame.fighter()) {
            glowSize *= 0.66f;
        }

        if (glowAlpha < 0.5f) {
            glowSize *= 0.15f + 0.85f * (glowAlpha / 0.5f);
        }

        // NaN 语义：maxSpread==0 时 glowAlpha 为 NaN，NaN > 0 为 false → 不发射（与原版一致）
        if (!(glowAlpha > 0.0f)) {
            return;
        }

        Color glowColor = slot.glowColor() != null ? slot.glowColor() : slot.color();
        float glowSizeMult = slot.glowSizeMult(); // var38 / var32
        float pulseSize = Math.min(glowSize, 15.0f) * 1.0f * primaryBrightness; // var39 / var33
        pulseSize *= 1.0f + glowBrightness;

        // 外圈：glow 色，alpha = (byte)(color.getAlpha() * alphaMult)
        float outerSize = glowSizeMult * (glowSize * 2.0f + pulseSize);
        int outerAlpha = ((byte) (glowColor.getAlpha() * glowAlpha)) & 0xFF;
        out.add(new GlowInstance(
                slot.posX(), slot.posY(), slot.midArcAngle(), glowRotation, glowScaleX,
                outerSize,
                0.0f, 0.0f, frame.glowSpriteUScale(), frame.glowSpriteVScale(),
                glowColor.getRed(), glowColor.getGreen(), glowColor.getBlue(),
                outerAlpha, frame.glowSpriteTextureId()));

        // 内芯：白色，alpha = (byte)(255 * alphaMult)
        float innerSize = glowSizeMult * glowSize * 0.75f;
        int innerAlpha = ((byte) (255 * glowAlpha)) & 0xFF;
        out.add(new GlowInstance(
                slot.posX(), slot.posY(), slot.midArcAngle(), glowRotation, glowScaleX,
                innerSize,
                0.0f, 0.0f, frame.glowSpriteUScale(), frame.glowSpriteVScale(),
                255, 255, 255,
                innerAlpha, frame.glowSpriteTextureId()));
    }

    /**
     * 原版 {@code setGlColor(color, (int) expr)} 的 alpha 语义：(int) 截断后 (byte) 取低 8 位。
     *
     * @param value 截断前的浮点表达式值
     * @return 0..255 的无符号字节值
     */
    public static int glColorByte(float value) {
        return ((byte) (int) value) & 0xFF;
    }

    // ---------------------------------------------------------------------
    // VBO_BATCH 模式：CPU 展开为三角形顶点（x,y,u,v + rgba，20 字节/顶点）
    // ---------------------------------------------------------------------

    /**
     * 展开尾焰条带实例为 6 个顶点（原版 quad-strip 顶点序）。
     * 矩阵等价链：T(pos)·R(angle)·R(rotation1)·R(rotation2)·T(translateX)·S(scaleX,scaleY)。
     */
    public static void expandStripVertices(StripInstance in, ByteBuffer out) {
        float halfWidth = in.halfWidth();
        for (int vi = 0; vi < 6; vi++) {
            float px = vi < 2 ? 0.0f : (vi < 4 ? in.innerLength() : in.stripLength());
            float py = (vi & 1) == 0 ? -halfWidth : halfWidth;
            float u = vi < 2 ? in.texU() : (vi < 4 ? in.texU() + in.texSpan() : in.texU() + in.texAdvance());
            float v = (vi & 1) == 0 ? TEX_MIN : TEX_MAX;
            int alpha = vi < 2 ? in.alphaStart() : (vi < 4 ? in.alphaMid() : 0);

            float x = px * in.scaleX() + in.translateX();
            float y = py * in.scaleY();
            float[] p = rotate(x, y, in.rotation2());
            p = rotate(p[0], p[1], in.rotation1());
            p = rotate(p[0], p[1], in.angle());

            putVertex(out, in.posX() + p[0], in.posY() + p[1], u, v, in.red(), in.green(), in.blue(), alpha);
        }
    }

    /**
     * 展开火焰核心实例为 4 个顶点（原版 quad-strip 顶点序）。
     * 矩阵等价链：T(pos)·R(angle)·R(coreRotation)·S(0.9,1)·R(omegaRotation)。
     */
    public static void expandCoreVertices(CoreInstance in, ByteBuffer out) {
        for (int vi = 0; vi < 4; vi++) {
            float px = vi < 2 ? 0.0f : in.stripLength();
            float py = (vi & 1) == 0 ? -in.halfWidth() : in.halfWidth();
            float u = vi < 2 ? TEX_PAD : 1.0f - TEX_PAD;
            float v = (vi & 1) == 0 ? TEX_MIN : TEX_MAX;

            float[] p = rotate(px, py, in.omegaRotation());
            p[0] *= 0.9f;
            p = rotate(p[0], p[1], in.coreRotation());
            p = rotate(p[0], p[1], in.angle());

            putVertex(out, in.posX() + p[0], in.posY() + p[1], u, v, in.red(), in.green(), in.blue(), in.alpha());
        }
    }

    /**
     * 展开辉光精灵实例为 4 个顶点（原版 Sprite.render 的 GL_QUADS 顶点序：
     * (0,0)→(0,S)→(S,S)→(S,0)，先平移 -S/2）。
     * 矩阵等价链：T(pos)·R(angle)·R(coreRotation)·S(scaleX,1)·T(-S/2,-S/2)。
     */
    public static void expandGlowVertices(GlowInstance in, ByteBuffer out) {
        float half = in.size() / 2.0f;
        for (int vi = 0; vi < 4; vi++) {
            float cx = vi == 2 || vi == 3 ? in.size() : 0.0f;
            float cy = vi == 1 || vi == 2 ? in.size() : 0.0f;
            float u = vi == 2 || vi == 3 ? in.texU1() : in.texU0();
            float v = vi == 1 || vi == 2 ? in.texV1() : in.texV0();

            float qx = (cx - half) * in.scaleX();
            float qy = cy - half;
            float[] p = rotate(qx, qy, in.coreRotation());
            p = rotate(p[0], p[1], in.angle());

            putVertex(out, in.posX() + p[0], in.posY() + p[1], u, v, in.red(), in.green(), in.blue(), in.alpha());
        }
    }

    // ---------------------------------------------------------------------
    // native flush：批次扁平化（命令表 + 定长实例数组，单次 JNI 传入）
    // ---------------------------------------------------------------------

    /**
     * 将一个批次扁平化到 direct ByteBuffer（必须 nativeOrder）：
     * 头部为 commandCount 条 {@link #COMMAND_BYTES} 字节的命令记录
     * （stage / textureId / instanceCount / dataOffset），随后依次是条带、核心、
     * 辉光三个阶段的定长实例数组；命令的 dataOffset 指向对应实例数组起点。
     *
     * @param batch 收集结果（允许为空批次，产出 0 条命令）
     * @param out   输出缓冲（容量必须 ≥ {@link #flattenedBytes(CollectedBatch)}）
     * @return 命令条数
     */
    public static int flatten(CollectedBatch batch, ByteBuffer out) {
        int commandCount = batch.strips.size() + batch.cores.size() + batch.glows.size();
        int stripPos = commandCount * COMMAND_BYTES;
        int corePos = stripPos + instanceCount(batch.strips) * STRIP_INSTANCE_BYTES;
        int glowPos = corePos + instanceCount(batch.cores) * CORE_INSTANCE_BYTES;

        int commandPos = 0;
        for (StripGroup group : batch.strips) {
            putCommand(out, commandPos, STAGE_STRIP, group.textureId(),
                    group.instances().size(), stripPos);
            commandPos += COMMAND_BYTES;
            for (StripInstance in : group.instances()) {
                putStripInstance(out, stripPos, in);
                stripPos += STRIP_INSTANCE_BYTES;
            }
        }
        for (CoreGroup group : batch.cores) {
            putCommand(out, commandPos, STAGE_CORE, group.textureId(),
                    group.instances().size(), corePos);
            commandPos += COMMAND_BYTES;
            for (CoreInstance in : group.instances()) {
                putCoreInstance(out, corePos, in);
                corePos += CORE_INSTANCE_BYTES;
            }
        }
        for (GlowGroup group : batch.glows) {
            putCommand(out, commandPos, STAGE_GLOW, group.textureId(),
                    group.instances().size(), glowPos);
            commandPos += COMMAND_BYTES;
            for (GlowInstance in : group.instances()) {
                putGlowInstance(out, glowPos, in);
                glowPos += GLOW_INSTANCE_BYTES;
            }
        }
        return commandCount;
    }

    /** 扁平化一个批次所需的总字节数（命令表 + 全部实例数据）。 */
    public static int flattenedBytes(CollectedBatch batch) {
        int commandCount = batch.strips.size() + batch.cores.size() + batch.glows.size();
        return commandCount * COMMAND_BYTES
                + instanceCount(batch.strips) * STRIP_INSTANCE_BYTES
                + instanceCount(batch.cores) * CORE_INSTANCE_BYTES
                + instanceCount(batch.glows) * GLOW_INSTANCE_BYTES;
    }

    /** 一个批次展开后的顶点字节总数（native 环形 VBO 容量预检用）。 */
    public static int expandedVertexBytes(CollectedBatch batch) {
        return (instanceCount(batch.strips) * 6
                + instanceCount(batch.cores) * 4
                + instanceCount(batch.glows) * 4) * VERTEX_BYTES;
    }

    /** 一个批次展开后的索引字节总数（native 环形 VBO 容量预检用）。 */
    public static int expandedIndexBytes(CollectedBatch batch) {
        return (instanceCount(batch.strips) * 12
                + instanceCount(batch.cores) * 6
                + instanceCount(batch.glows) * 6) * 2;
    }

    private static int instanceCount(List<? extends EngineGroup> groups) {
        int total = 0;
        for (EngineGroup group : groups) {
            total += group.instanceCount();
        }
        return total;
    }

    private static void putCommand(ByteBuffer out, int pos, int stage, int textureId,
                                   int instanceCount, int dataOffset) {
        out.putInt(pos, stage);
        out.putInt(pos + 4, textureId);
        out.putInt(pos + 8, instanceCount);
        out.putInt(pos + 12, dataOffset);
    }

    private static void putStripInstance(ByteBuffer out, int pos, StripInstance in) {
        out.putFloat(pos, in.posX());
        out.putFloat(pos + 4, in.posY());
        out.putFloat(pos + 8, in.angle());
        out.putFloat(pos + 12, in.rotation1());
        out.putFloat(pos + 16, in.rotation2());
        out.putFloat(pos + 20, in.translateX());
        out.putFloat(pos + 24, in.scaleX());
        out.putFloat(pos + 28, in.scaleY());
        out.putFloat(pos + 32, in.halfWidth());
        out.putFloat(pos + 36, in.innerLength());
        out.putFloat(pos + 40, in.stripLength());
        out.putFloat(pos + 44, in.texU());
        out.putFloat(pos + 48, in.texSpan());
        out.putFloat(pos + 52, in.texAdvance());
        out.put(pos + 56, (byte) in.red());
        out.put(pos + 57, (byte) in.green());
        out.put(pos + 58, (byte) in.blue());
        out.put(pos + 59, (byte) in.alphaStart());
        out.put(pos + 60, (byte) in.alphaMid());
    }

    private static void putCoreInstance(ByteBuffer out, int pos, CoreInstance in) {
        out.putFloat(pos, in.posX());
        out.putFloat(pos + 4, in.posY());
        out.putFloat(pos + 8, in.angle());
        out.putFloat(pos + 12, in.coreRotation());
        out.putFloat(pos + 16, in.omegaRotation());
        out.putFloat(pos + 20, in.stripLength());
        out.putFloat(pos + 24, in.halfWidth());
        out.put(pos + 28, (byte) in.red());
        out.put(pos + 29, (byte) in.green());
        out.put(pos + 30, (byte) in.blue());
        out.put(pos + 31, (byte) in.alpha());
    }

    private static void putGlowInstance(ByteBuffer out, int pos, GlowInstance in) {
        out.putFloat(pos, in.posX());
        out.putFloat(pos + 4, in.posY());
        out.putFloat(pos + 8, in.angle());
        out.putFloat(pos + 12, in.coreRotation());
        out.putFloat(pos + 16, in.scaleX());
        out.putFloat(pos + 20, in.size());
        out.putFloat(pos + 24, in.texU0());
        out.putFloat(pos + 28, in.texV0());
        out.putFloat(pos + 32, in.texU1());
        out.putFloat(pos + 36, in.texV1());
        out.put(pos + 40, (byte) in.red());
        out.put(pos + 41, (byte) in.green());
        out.put(pos + 42, (byte) in.blue());
        out.put(pos + 43, (byte) in.alpha());
    }

    // ---------------------------------------------------------------------
    // 索引生成（GL_TRIANGLES，无符号短整型）
    // ---------------------------------------------------------------------

    /** 追加一个条带实例的 12 个三角形索引（quad-strip 6 顶点 → 2 quad → 4 三角形）。 */
    public static void appendStripIndices(ByteBuffer out, int baseVertex) {
        // quad0: (0,1,3,2)，quad1: (2,3,5,4)
        putIndex(out, baseVertex);
        putIndex(out, baseVertex + 1);
        putIndex(out, baseVertex + 3);
        putIndex(out, baseVertex);
        putIndex(out, baseVertex + 3);
        putIndex(out, baseVertex + 2);
        putIndex(out, baseVertex + 2);
        putIndex(out, baseVertex + 3);
        putIndex(out, baseVertex + 5);
        putIndex(out, baseVertex + 2);
        putIndex(out, baseVertex + 5);
        putIndex(out, baseVertex + 4);
    }

    /** 追加一个核心实例的 6 个三角形索引（quad-strip 4 顶点 → 1 quad）。 */
    public static void appendCoreIndices(ByteBuffer out, int baseVertex) {
        putIndex(out, baseVertex);
        putIndex(out, baseVertex + 1);
        putIndex(out, baseVertex + 3);
        putIndex(out, baseVertex);
        putIndex(out, baseVertex + 3);
        putIndex(out, baseVertex + 2);
    }

    /** 追加一个辉光实例的 6 个三角形索引（GL_QUADS 4 顶点 → 2 三角形）。 */
    public static void appendGlowIndices(ByteBuffer out, int baseVertex) {
        putIndex(out, baseVertex);
        putIndex(out, baseVertex + 1);
        putIndex(out, baseVertex + 2);
        putIndex(out, baseVertex);
        putIndex(out, baseVertex + 2);
        putIndex(out, baseVertex + 3);
    }

    private static void putIndex(ByteBuffer out, int index) {
        out.putShort((short) index);
    }

    private static void putVertex(ByteBuffer out,
                                  float x, float y, float u, float v,
                                  int r, int g, int b, int a) {
        out.putFloat(x).putFloat(y).putFloat(u).putFloat(v);
        out.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
    }

    private static float[] rotate(float x, float y, float angleDegrees) {
        float radians = angleDegrees * DEG_TO_RAD;
        float sin = (float) Math.sin(radians);
        float cos = (float) Math.cos(radians);
        return new float[]{x * cos - y * sin, x * sin + y * cos};
    }
}
