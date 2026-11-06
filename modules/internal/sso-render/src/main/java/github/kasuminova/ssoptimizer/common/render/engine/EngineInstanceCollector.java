package github.kasuminova.ssoptimizer.common.render.engine;

import java.awt.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class EngineInstanceCollector {
    private static final float DEG_TO_RAD = 0.017453292519943295769f;
    public static final float TEX_PAD = 0.01f;
    public static final float TEX_MIN = 0.01f;
    public static final float TEX_MAX = 0.99f;

    public static final int VERTEX_BYTES = 20;

    // 以下布局必须与 ssoptimizer_engine_batch.cpp 同步，并使用 nativeOrder 写入。
    public static final int STAGE_STRIP = 0;
    public static final int STAGE_CORE  = 1;
    public static final int STAGE_GLOW  = 2;

    public static final int COMMAND_BYTES = 16;
    public static final int STRIP_INSTANCE_BYTES = 64;
    public static final int CORE_INSTANCE_BYTES = 32;
    public static final int GLOW_INSTANCE_BYTES = 44;

    private EngineInstanceCollector() {
    }

    public enum Stage {
        FLAME_STRIP,
        FLAME_CORE,
        GLOW_SPRITE
    }

    public record GroupKey(Stage stage, int textureId) {
    }

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

    public interface EngineGroup {
        int textureId();

        int instanceCount();
    }

    public record StripGroup(int textureId, List<StripInstance> instances) implements EngineGroup {
        public GroupKey key() {
            return new GroupKey(Stage.FLAME_STRIP, textureId);
        }

        @Override
        public int instanceCount() {
            return instances.size();
        }
    }

    public record CoreGroup(int textureId, List<CoreInstance> instances) implements EngineGroup {
        public GroupKey key() {
            return new GroupKey(Stage.FLAME_CORE, textureId);
        }

        @Override
        public int instanceCount() {
            return instances.size();
        }
    }

    public record GlowGroup(int textureId, List<GlowInstance> instances) implements EngineGroup {
        public GroupKey key() {
            return new GroupKey(Stage.GLOW_SPRITE, textureId);
        }

        @Override
        public int instanceCount() {
            return instances.size();
        }
    }

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

    public static void collect(FrameInput frame, List<SlotInput> slots, CollectedBatch out) {
        float angularRotation = -(frame.angularVelocity() * 0.15f);
        if (frame.omegaMode()) {
            float sign = Math.signum(-frame.angularVelocity());
            float ratio = Math.min(1.0f, Math.abs(frame.angularVelocity()) / 120.0f);
            ratio *= ratio;
            angularRotation = sign * ratio * 10.0f;
        }

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

    private static void collectShipSlot(FrameInput frame, SlotInput slot,
                                        float angularRotation, float glowBrightness,
                                        CollectedBatch out) {
        float flameLevel = slot.flameLevel();
        float adjustedLevel = slot.adjustedLevel();
        float spread = slot.spread();
        float maxSpread = slot.maxSpread();
        Color color = slot.color();

        float edgeAlpha = flameLevel / 0.4f;
        if (edgeAlpha > 1.0f) {
            edgeAlpha = 1.0f;
        }

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

        // maxSpread 为 0 时保留 NaN 传播，不做除零保护。
        if (frame.omegaMode()) {
            widthFactor *= (spread * 2.0f + maxSpread) / maxSpread;
        } else {
            widthFactor *= (spread + maxSpread) / maxSpread;
        }

        float primaryBrightness = frame.primaryBrightness();
        float length = slot.slotLength() + slot.slotLength() * 0.25f * primaryBrightness;
        length += slot.slotLength() * frame.lengthShiftCurr();
        float width = slot.slotWidth();
        width += slot.slotWidth() * frame.widthShiftCurr();

        float stripLength = length * (0.2f + lengthFactor * 0.8f);
        float innerWidth = slot.slotWidth() * (0.1f + widthFactor * 0.9f);
        float stripWidth = width * (0.1f + widthFactor * 0.9f);

        if (!frame.withSpread()) {
            float spreadRatio = spread / 90.0f;
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

        float spreadRotation = (1.0f - stripLength / length) * spread;
        float textureAdvance = flameLevel * 1.0f;
        float passCount = 6.0f;
        if (frame.omegaMode()) {
            passCount = 1.0f;
            spreadRotation = 0.0f;
        }

        float texU = slot.texU();
        float innerLength = Math.min(innerWidth / 2.0f, stripLength / 4.0f);
        float texSpan = innerLength / stripLength;

        int stripTextureId = slot.primaryGlowType()
                ? frame.stripPrimaryTextureId()
                : frame.stripSecondaryTextureId();
        int layerCount = frame.omegaMode() ? 2 : 1;
        int colorAlpha = color.getAlpha();

        for (int layer = 0; layer < layerCount; layer++) {
            for (int pass = 0; pass < (int) passCount; pass++) {
                float passF = pass;
                float rotation1 = (passCount - passF - 1.0f) / passCount * angularRotation;
                if (frame.omegaMode()) {
                    rotation1 = angularRotation;
                }
                float direction = 1.0f;
                if (passF % 2.0f == 0.0f) {
                    direction = -1.0f;
                }
                float phase = (passF + 1.0f) / 2.0f;
                float rotation2 = (passCount / 2.0f - phase - 1.0f) / (passCount / 2.0f)
                        * direction * 2.0f * spreadRotation;
                float translateX = (passCount - passF - 1.0f) * innerLength / (passCount * 2.0f);
                float scaleX = 0.5f + 0.5f * (passF + 1.0f) / passCount;
                float scaleY = 1.0f * (passCount - passF) / passCount;

                int alphaStart = glColorByte(passF * 5.0f * colorAlpha / 255.0f
                        * frame.alphaScale() * edgeAlpha);
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

        float omegaRotation = frame.omegaMode() ? angularRotation : 0.0f;
        int coreAlpha = glColorByte(flameLevel * 50.0f * colorAlpha / 255.0f
                * frame.alphaScale() * edgeAlpha);
        out.add(new CoreInstance(
                slot.posX(), slot.posY(), slot.midArcAngle(),
                slot.coreRotation(), omegaRotation,
                stripLength, stripWidth * 0.5f,
                color.getRed(), color.getGreen(), color.getBlue(),
                coreAlpha, frame.flameTextureId()));

        // 舰船辉光继承核心 pass 的旋转和 0.9 横向缩放。
        collectGlow(frame, slot, glowBrightness, primaryBrightness, edgeAlpha,
                spread, maxSpread, innerWidth, stripWidth,
                slot.coreRotation(), 0.9f, out);
    }

    private static void collectFighterSlot(FrameInput frame, SlotInput slot,
                                           float angularRotation, float glowBrightness,
                                           CollectedBatch out) {
        float flameLevel = slot.flameLevel();
        float adjustedLevel = slot.adjustedLevel();
        float spread = slot.spread();
        float maxSpread = slot.maxSpread();
        Color color = slot.color();

        float edgeAlpha = flameLevel / 0.4f;
        if (edgeAlpha > 1.0f) {
            edgeAlpha = 1.0f;
        }

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
        widthFactor *= (spread + maxSpread) / maxSpread;

        float primaryBrightness = frame.primaryBrightness();
        float length = slot.slotLength() + slot.slotLength() * 0.25f * primaryBrightness;
        length += slot.slotLength() * frame.lengthShiftCurr();
        float width = slot.slotWidth();
        width += slot.slotWidth() * frame.widthShiftCurr();

        float stripLength = length * (0.2f + lengthFactor * 0.8f);
        float innerWidth = slot.slotWidth() * (0.1f + widthFactor * 0.9f);
        float stripWidth = width * (0.1f + widthFactor * 0.9f);

        float textureAdvance = flameLevel * 1.0f;
        float passCount = 3.0f;
        float texU = slot.texU();
        float innerLength = Math.min(innerWidth / 2.0f, stripLength / 4.0f);
        float texSpan = innerLength / stripLength;

        int stripTextureId = slot.primaryGlowType()
                ? frame.stripPrimaryTextureId()
                : frame.stripSecondaryTextureId();
        int colorAlpha = color.getAlpha();

        for (int pass = 0; pass < (int) passCount; pass++) {
            float passF = pass;
            float rotation1 = (passCount - passF - 1.0f) / passCount * angularRotation;
            float translateX = (passCount - passF - 1.0f) * innerLength / (passCount * 2.0f);
            float scaleX = 0.5f + 0.5f * (passF + 1.0f) / passCount;
            float scaleY = 1.0f * (passCount - passF) / passCount;

            int alphaStart = glColorByte(passF * 5.0f * colorAlpha / 255.0f
                    * frame.alphaScale() * edgeAlpha);
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

        collectGlow(frame, slot, glowBrightness, primaryBrightness, edgeAlpha,
                spread, maxSpread, innerWidth, stripWidth,
                0.0f, 1.0f, out);
    }

    private static void collectGlow(FrameInput frame, SlotInput slot,
                                    float glowBrightness, float primaryBrightness,
                                    float edgeAlpha,
                                    float spread, float maxSpread,
                                    float innerWidth, float stripWidth,
                                    float glowRotation, float glowScaleX,
                                    CollectedBatch out) {
        if (frame.glowSpriteTextureId() < 0) {
            return;
        }

        float glowSize;
        if (!frame.shiftersShifted()) {
            glowSize = stripWidth * (2.0f + 1.0f * primaryBrightness);
        } else {
            float extra = (stripWidth - innerWidth * frame.widthShiftCurr()) * 2.0f;
            glowSize = extra + extra * 0.5f * primaryBrightness;
            glowSize += extra * frame.glowShiftCurr();
        }

        float glowAlphaBase = Math.max(primaryBrightness * 0.25f, spread / maxSpread * 0.5f);
        glowAlphaBase = Math.max(glowAlphaBase, glowBrightness);
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

        // NaN 必须走不发射分支。
        if (!(glowAlpha > 0.0f)) {
            return;
        }

        Color glowColor = slot.glowColor() != null ? slot.glowColor() : slot.color();
        float glowSizeMult = slot.glowSizeMult();
        float pulseSize = Math.min(glowSize, 15.0f) * 1.0f * primaryBrightness;
        pulseSize *= 1.0f + glowBrightness;

        float outerSize = glowSizeMult * (glowSize * 2.0f + pulseSize);
        int outerAlpha = ((byte) (glowColor.getAlpha() * glowAlpha)) & 0xFF;
        out.add(new GlowInstance(
                slot.posX(), slot.posY(), slot.midArcAngle(), glowRotation, glowScaleX,
                outerSize,
                0.0f, 0.0f, frame.glowSpriteUScale(), frame.glowSpriteVScale(),
                glowColor.getRed(), glowColor.getGreen(), glowColor.getBlue(),
                outerAlpha, frame.glowSpriteTextureId()));

        float innerSize = glowSizeMult * glowSize * 0.75f;
        int innerAlpha = ((byte) (255 * glowAlpha)) & 0xFF;
        out.add(new GlowInstance(
                slot.posX(), slot.posY(), slot.midArcAngle(), glowRotation, glowScaleX,
                innerSize,
                0.0f, 0.0f, frame.glowSpriteUScale(), frame.glowSpriteVScale(),
                255, 255, 255,
                innerAlpha, frame.glowSpriteTextureId()));
    }

    /** 保持 setGlColor 先截断再取低 8 位的语义，不做钳制。 */
    public static int glColorByte(float value) {
        return ((byte) (int) value) & 0xFF;
    }

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

    public static int flattenedBytes(CollectedBatch batch) {
        int commandCount = batch.strips.size() + batch.cores.size() + batch.glows.size();
        return commandCount * COMMAND_BYTES
                + instanceCount(batch.strips) * STRIP_INSTANCE_BYTES
                + instanceCount(batch.cores) * CORE_INSTANCE_BYTES
                + instanceCount(batch.glows) * GLOW_INSTANCE_BYTES;
    }

    public static int expandedVertexBytes(CollectedBatch batch) {
        return (instanceCount(batch.strips) * 6
                + instanceCount(batch.cores) * 4
                + instanceCount(batch.glows) * 4) * VERTEX_BYTES;
    }

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

    public static void appendStripIndices(ByteBuffer out, int baseVertex) {
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

    public static void appendCoreIndices(ByteBuffer out, int baseVertex) {
        putIndex(out, baseVertex);
        putIndex(out, baseVertex + 1);
        putIndex(out, baseVertex + 3);
        putIndex(out, baseVertex);
        putIndex(out, baseVertex + 3);
        putIndex(out, baseVertex + 2);
    }

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
