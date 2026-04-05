package github.kasuminova.ssoptimizer.common.render.engine;

import com.fs.graphics.Sprite;
import com.fs.starfarer.loading.specs.EngineSlot;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import github.kasuminova.ssoptimizer.mixin.accessor.EngineOwnerAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.EngineSlotAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.EngineStateAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.ShipAccessor;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.util.List;

/**
 * 引擎喷口批量渲染 helper。
 * <p>
 * 用于替换原版 {@code com.fs.starfarer.combat.entities.Engine.render(float)} 中
 * 大量重复的即时模式 GL 调用，统一走条带/核心批量绘制路径。
 */
public final class EngineRenderHelper {
    private static final String                   RENDER_STATE_UTILS_CLASS = GameClassNames.RENDER_STATE_UTILS.replace('/', '.');
    private static final java.lang.reflect.Method BLEND_COLORS_METHOD      = resolveBlendColorsMethod();
    private static final float                    DEG_TO_RAD               = 0.017453292519943295769f;
    private static final float                    TEX_PAD                  = 0.01f;
    private static final float                    TEX_MIN                  = 0.01f;
    private static final float                    TEX_MAX                  = 0.99f;

    private EngineRenderHelper() {
    }

    public static void renderEngines(Object engineObject, float alphaScale) {
        EngineBridge engine = (EngineBridge) engineObject;
        EngineOwnerAccessor owner = (EngineOwnerAccessor) engine.ssoptimizer$getOwner();

        if (owner.ssoptimizer$isFighter()) {
            engine.ssoptimizer$renderFighter(alphaScale);
            return;
        }

        List<EngineSlot> engineSlots = owner.ssoptimizer$getEngineLocations();
        boolean omegaMode = !engineSlots.isEmpty()
                && ((EngineSlotAccessor) engineSlots.get(0)).ssoptimizer$isOmegaMode();
        boolean withSpread = engineSlots.isEmpty()
                || ((EngineSlotAccessor) engineSlots.get(0)).ssoptimizer$isWithSpread();

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(770, 1);

        float angularRotation = -owner.ssoptimizer$getAngularVelocity() * 0.15f;
        if (omegaMode) {
            float sign = Math.signum(-owner.ssoptimizer$getAngularVelocity());
            float ratio = Math.min(1.0f, Math.abs(owner.ssoptimizer$getAngularVelocity()) / 120.0f);
            angularRotation = sign * ratio * ratio * 10.0f;
        }

        for (EngineSlot slot : engineSlots) {
            renderSlot(engine, owner, slot, alphaScale, omegaMode, withSpread, angularRotation);
        }
    }

    static boolean requiresExactAlphaNativePath(float alphaScale) {
        return alphaScale < 0.999f;
    }

    private static void renderSlot(EngineBridge engine,
                                   EngineOwnerAccessor owner,
                                   EngineSlot slot,
                                   float alphaScale,
                                   boolean omegaMode,
                                   boolean withSpread,
                                   float angularRotation) {
        EngineSlotAccessor slotAccessor = (EngineSlotAccessor) slot;
        EngineStateAccessor state = (EngineStateAccessor) engine.ssoptimizer$getState(slot);

        float flameLevel = state.ssoptimizer$getFlameLevel();
        float adjustedLevel = flameLevel;

        if (slotAccessor.ssoptimizer$isSystemActivated() && engine.ssoptimizer$getOwner() instanceof ShipAccessor shipAccessor) {
            if (!engine.ssoptimizer$isSystemActivatedRenderingEnabled()) {
                return;
            }
            flameLevel *= engine.ssoptimizer$getLengthShifter().getShiftProgress(shipAccessor.ssoptimizer$getSystem());
            if (flameLevel < 0.25f) {
                adjustedLevel = 0.0f;
            } else {
                adjustedLevel = (flameLevel - 0.25f) / 0.75f;
            }
        }

        if (flameLevel == 0.0f) {
            return;
        }

        float facing = owner.ssoptimizer$getFacing();
        float angle = slotAccessor.ssoptimizer$computeMidArcAngle(facing);
        Vector2f position = slotAccessor.ssoptimizer$computePosition(new Vector2f(), facing);
        float spread = state.ssoptimizer$getSpread();
        float maxSpread = slotAccessor.ssoptimizer$getMaxSpread();
        Color color = shiftedColor(engine, slotAccessor.ssoptimizer$getColor());
        float edgeAlpha = Math.min(flameLevel / 0.4f, 1.0f);

        float widthFactor;
        float lengthFactor;
        if (engine.ssoptimizer$isBoostedFlameMode()) {
            widthFactor = Math.min(adjustedLevel, 0.8f) / 0.8f;
            widthFactor *= widthFactor;
            widthFactor = Math.max(widthFactor, 0.45000005f);

            lengthFactor = Math.max(0.0f, adjustedLevel - 0.8f) / 0.19999999f;
            lengthFactor *= lengthFactor;
        } else {
            widthFactor = 0.09f + Math.max(0.0f, adjustedLevel - 0.8f) / 0.19999999f;
            lengthFactor = Math.max(0.0f, adjustedLevel - 0.8f) / 0.19999999f;
        }

        if (maxSpread != 0.0f) {
            if (omegaMode) {
                widthFactor *= ((spread * 2.0f) + maxSpread) / maxSpread;
            } else {
                widthFactor *= (spread + maxSpread) / maxSpread;
            }
        }

        float primaryBrightness = engine.ssoptimizer$getPrimaryFader().getBrightness();
        float slotLength = slotAccessor.ssoptimizer$getLength();
        float slotWidth = slotAccessor.ssoptimizer$getWidth();
        float length = slotLength + slotLength * 0.25f * primaryBrightness;
        length += slotLength * engine.ssoptimizer$getLengthShifter().getCurr();
        float width = slotWidth + slotWidth * engine.ssoptimizer$getWidthShifter().getCurr();

        float stripLength = length * (0.2f + lengthFactor * 0.8f);
        float innerWidth = slotWidth * (0.1f + widthFactor * 0.9f);
        float stripWidth = width * (0.1f + widthFactor * 0.9f);

        if (!withSpread) {
            float spreadRatio = clamp01(spread / 90.0f);
            spread = 0.0f;
            stripLength *= 1.0f - spreadRatio * 0.5f;
            stripWidth *= 1.0f + spreadRatio * 0.25f;
        }

        float spreadRotation = length == 0.0f ? 0.0f : (1.0f - stripLength / length) * spread;
        float textureAdvance = flameLevel;
        int passCount = omegaMode ? 1 : 6;
        float texU = state.ssoptimizer$getTexU();
        float innerLength = Math.min(innerWidth * 0.5f, stripLength * 0.25f);
        float texSpan = stripLength == 0.0f ? 0.0f : innerLength / stripLength;

        if (isPrimaryGlowType(slotAccessor.ssoptimizer$getGlowType())) {
            engine.ssoptimizer$getPrimaryGlowTexture().bind();
        } else {
            engine.ssoptimizer$getSecondaryGlowTexture().bind();
        }

        int red = color.getRed();
        int green = color.getGreen();
        int blue = color.getBlue();
        float colorAlphaScale = (color.getAlpha() / 255.0f) * alphaScale * edgeAlpha;
        boolean exactAlphaPath = requiresExactAlphaNativePath(alphaScale);
        int layerCount = omegaMode ? 2 : 1;

        renderEngineStripPasses(position.x, position.y, angle, angularRotation, spreadRotation,
                passCount, layerCount, texU, texSpan, textureAdvance,
                innerLength, stripLength, stripWidth,
                red, green, blue, colorAlphaScale, exactAlphaPath);

        engine.ssoptimizer$getFlameTexture().bind();
        int coreAlpha = clampColorComponent((int) (flameLevel * 50.0f * colorAlphaScale));
        renderEngineCorePass(position.x, position.y, angle,
                state.ssoptimizer$getCoreRotation(), omegaMode ? angularRotation : 0.0f,
                stripLength, stripWidth,
                red, green, blue, coreAlpha, exactAlphaPath);

        renderGlowSprite(engine, owner, slotAccessor, state, position, angle,
            flameLevel, primaryBrightness, edgeAlpha, spread, maxSpread,
                innerWidth, stripWidth, color, alphaScale);
    }

    private static void renderGlowSprite(EngineBridge engine,
                                         EngineOwnerAccessor owner,
                                         EngineSlotAccessor slotAccessor,
                                         EngineStateAccessor state,
                                         Vector2f position,
                                         float angle,
                         float flameLevel,
                                         float primaryBrightness,
                                         float edgeAlpha,
                                         float spread,
                                         float maxSpread,
                                         float innerWidth,
                                         float stripWidth,
                                         Color baseColor,
                                         float alphaScale) {
        float glowBrightness = engine.ssoptimizer$getSecondaryFader().getBrightness();
        if (glowBrightness > 0.0f) {
            glowBrightness = (float) Math.sqrt(glowBrightness);
        }
        glowBrightness *= 0.75f;

        float glowSize;
        if (engine.ssoptimizer$getLengthShifter().isShifted()
                || engine.ssoptimizer$getWidthShifter().isShifted()
                || engine.ssoptimizer$getGlowShifter().isShifted()) {
            float extraWidth = (stripWidth - innerWidth) * 2.0f;
            glowSize = extraWidth + extraWidth * 0.5f * primaryBrightness;
            glowSize += extraWidth * engine.ssoptimizer$getGlowShifter().getCurr();
        } else {
            glowSize = stripWidth * 2.0f * (1.0f + primaryBrightness);
        }

        float glowAlphaBase = 0.0f;
        if (maxSpread != 0.0f) {
            glowAlphaBase = Math.max(primaryBrightness * 0.25f, (spread / maxSpread) * 0.5f);
        }
        glowAlphaBase = Math.max(glowAlphaBase, glowBrightness);

        float glowAlpha = computeGlowAlpha(glowAlphaBase, flameLevel, edgeAlpha, alphaScale);

        if (owner.ssoptimizer$isMissile()) {
            glowSize *= 2.0f;
        } else if (owner.ssoptimizer$isFighter()) {
            glowSize *= 0.66f;
        }

        if (glowAlpha < 0.5f) {
            glowSize *= 0.15f + 0.85f * (glowAlpha / 0.5f);
        }

        if (glowAlpha <= 0.0f) {
            return;
        }

        Color glowColor = baseColor;
        Color alternateColor = slotAccessor.ssoptimizer$getGlowAlternateColor();
        if (alternateColor != null) {
            glowColor = shiftedColor(engine, alternateColor);
        }

        float glowSizeMult = slotAccessor.ssoptimizer$getGlowSizeMult();
        Sprite sprite = engine.ssoptimizer$getGlowSprite();
        sprite.setAlphaMult(glowAlpha);
        sprite.setColor(glowColor);

        float pulseSize = Math.min(glowSize, 15.0f) * primaryBrightness;
        pulseSize *= 1.0f + glowBrightness;
        float outerSize = glowSizeMult * (glowSize * 2.0f + pulseSize);
        float innerSize = glowSizeMult * glowSize * 0.75f;

        GL11.glPushMatrix();
        GL11.glTranslatef(position.x, position.y, 0.0f);
        GL11.glRotatef(angle, 0.0f, 0.0f, 1.0f);
        GL11.glRotatef(state.ssoptimizer$getCoreRotation(), 0.0f, 0.0f, 1.0f);
        GL11.glScalef(0.9f, 1.0f, 1.0f);

        sprite.setSize(outerSize, outerSize);
        sprite.renderAtCenter(0.0f, 0.0f);

        sprite.setColor(Color.white);
        sprite.setSize(innerSize, innerSize);
        sprite.renderAtCenter(0.0f, 0.0f);

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glPopMatrix();
    }

    private static Color shiftedColor(EngineBridge engine, Color baseColor) {
        if (baseColor == null) {
            return null;
        }
        if (!engine.ssoptimizer$getColorShifter().isShifted()) {
            return baseColor;
        }
        try {
            return (Color) BLEND_COLORS_METHOD.invoke(null,
                    baseColor,
                    engine.ssoptimizer$getColorShifter().getCurrForBase(baseColor),
                    engine.ssoptimizer$getColorShiftFraction());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke render-state color blend helper", e);
        }
    }

    private static java.lang.reflect.Method resolveBlendColorsMethod() {
        try {
            final Class<?> helperClass = Class.forName(RENDER_STATE_UTILS_CLASS, false, EngineRenderHelper.class.getClassLoader());
            final java.lang.reflect.Method method = helperClass.getDeclaredMethod(
                    "blendColors",
                    Color.class,
                    Color.class,
                    float.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to resolve render-state color blend helper", e);
        }
    }

    private static boolean isPrimaryGlowType(Object glowType) {
        return glowType instanceof Enum<?> mode && GameMemberNames.EngineGlowType.PRIMARY.equals(mode.name());
    }

    private static void renderEngineStripPasses(float posX, float posY,
                                                float angle,
                                                float angularRotation,
                                                float spreadRotation,
                                                int passCount,
                                                int layerCount,
                                                float texUStart,
                                                float texSpan,
                                                float textureAdvance,
                                                float innerLength,
                                                float stripLength,
                                                float stripWidth,
                                                int red, int green, int blue,
                                                float colorAlphaScale,
                                                boolean exactAlphaPath) {
        if (SpriteRenderHelper.isNativeLoaded()) {
            nativeRenderEngineStripBatch(posX, posY, angle, angularRotation, spreadRotation,
                    passCount, layerCount, texUStart, texSpan, textureAdvance,
                    innerLength, stripLength, stripWidth,
                    red, green, blue, colorAlphaScale, exactAlphaPath);
            return;
        }

        float texU = texUStart;
        float texUStep = passCount <= 0 ? 0.0f : 1.0f / passCount;
        int midAlpha = clampColorComponent((int) (100.0f * colorAlphaScale));
        for (int layer = 0; layer < layerCount; layer++) {
            for (int passIndex = 0; passIndex < passCount; passIndex++) {
                int startAlpha = clampColorComponent((int) (passIndex * 5.0f * colorAlphaScale));
                renderEngineStripPassFallback(posX, posY, angle, angularRotation, spreadRotation,
                        passCount, passIndex, texU, texSpan, textureAdvance,
                        innerLength, stripLength, stripWidth,
                        red, green, blue, startAlpha, midAlpha);
                texU += texUStep;
            }
        }
    }

    private static void renderEngineStripPassFallback(float posX, float posY,
                                                      float angle,
                                                      float angularRotation,
                                                      float spreadRotation,
                                                      float passCount,
                                                      int passIndex,
                                                      float texU,
                                                      float texSpan,
                                                      float textureAdvance,
                                                      float innerLength,
                                                      float stripLength,
                                                      float stripWidth,
                                                      int red, int green, int blue,
                                                      int startAlpha, int midAlpha) {
        float[] vertices = computeStripVertices(posX, posY, angle, angularRotation, spreadRotation,
                passCount, passIndex, innerLength, stripLength, stripWidth);

        GL11.glBegin(GL11.GL_QUAD_STRIP);
        emitStripVertex(red, green, blue, startAlpha, texU, TEX_MIN, vertices, 0);
        emitStripVertex(red, green, blue, startAlpha, texU, TEX_MAX, vertices, 2);
        emitStripVertex(red, green, blue, midAlpha, texU + texSpan, TEX_MIN, vertices, 4);
        emitStripVertex(red, green, blue, midAlpha, texU + texSpan, TEX_MAX, vertices, 6);
        emitStripVertex(red, green, blue, 0, texU + textureAdvance, TEX_MIN, vertices, 8);
        emitStripVertex(red, green, blue, 0, texU + textureAdvance, TEX_MAX, vertices, 10);
        GL11.glEnd();
    }

    private static void renderEngineCorePass(float posX, float posY,
                                             float angle,
                                             float stateRotation,
                                             float omegaRotation,
                                             float stripLength,
                                             float stripWidth,
                                             int red, int green, int blue, int alpha,
                                             boolean exactAlphaPath) {
        if (SpriteRenderHelper.isNativeLoaded()) {
            nativeRenderEngineCorePass(posX, posY, angle, stateRotation, omegaRotation,
                    stripLength, stripWidth,
                    red, green, blue, alpha, exactAlphaPath);
            return;
        }

        float[] vertices = computeCoreVertices(posX, posY, angle, stateRotation, omegaRotation,
                stripLength, stripWidth);

        GL11.glBegin(GL11.GL_QUAD_STRIP);
        emitStripVertex(red, green, blue, alpha, TEX_PAD, TEX_MIN, vertices, 0);
        emitStripVertex(red, green, blue, alpha, TEX_PAD, TEX_MAX, vertices, 2);
        emitStripVertex(red, green, blue, alpha, 1.0f - TEX_PAD, TEX_MIN, vertices, 4);
        emitStripVertex(red, green, blue, alpha, 1.0f - TEX_PAD, TEX_MAX, vertices, 6);
        GL11.glEnd();
    }

    static float[] computeStripVertices(float posX, float posY,
                                        float angle,
                                        float angularRotation,
                                        float spreadRotation,
                                        float passCount,
                                        int passIndex,
                                        float innerLength,
                                        float stripLength,
                                        float stripWidth) {
        float passIndexF = passIndex;
        float rotation1 = passCount <= 1.0f
                ? angularRotation
                : ((passCount - passIndexF - 1.0f) / passCount) * angularRotation;
        float direction = (passIndex % 2 == 0) ? -1.0f : 1.0f;
        float phase = (passIndexF + 1.0f) / 2.0f;
        float halfPassCount = passCount / 2.0f;
        float rotation2 = ((halfPassCount - phase - 1.0f) / halfPassCount) * direction * 2.0f * spreadRotation;
        float translateX = ((passCount - passIndexF - 1.0f) * innerLength) / (passCount * 2.0f);
        float scaleX = 0.5f + ((passIndexF + 1.0f) / passCount);
        float scaleY = 1.0f - ((passCount - passIndexF) / passCount);
        float halfWidth = stripWidth * 0.5f;

        float[] vertices = new float[12];
        transformStripVertex(vertices, 0, 0.0f, -halfWidth,
                posX, posY, angle, rotation1, rotation2, translateX, scaleX, scaleY);
        transformStripVertex(vertices, 2, 0.0f, halfWidth,
                posX, posY, angle, rotation1, rotation2, translateX, scaleX, scaleY);
        transformStripVertex(vertices, 4, innerLength, -halfWidth,
                posX, posY, angle, rotation1, rotation2, translateX, scaleX, scaleY);
        transformStripVertex(vertices, 6, innerLength, halfWidth,
                posX, posY, angle, rotation1, rotation2, translateX, scaleX, scaleY);
        transformStripVertex(vertices, 8, stripLength, -halfWidth,
                posX, posY, angle, rotation1, rotation2, translateX, scaleX, scaleY);
        transformStripVertex(vertices, 10, stripLength, halfWidth,
                posX, posY, angle, rotation1, rotation2, translateX, scaleX, scaleY);
        return vertices;
    }

    static float[] computeCoreVertices(float posX, float posY,
                                       float angle,
                                       float stateRotation,
                                       float omegaRotation,
                                       float stripLength,
                                       float stripWidth) {
        float[] vertices = new float[8];
        float halfWidth = stripWidth * 0.5f;
        transformCoreVertex(vertices, 0, 0.0f, -halfWidth,
                posX, posY, angle, stateRotation, omegaRotation);
        transformCoreVertex(vertices, 2, 0.0f, halfWidth,
                posX, posY, angle, stateRotation, omegaRotation);
        transformCoreVertex(vertices, 4, stripLength, -halfWidth,
                posX, posY, angle, stateRotation, omegaRotation);
        transformCoreVertex(vertices, 6, stripLength, halfWidth,
                posX, posY, angle, stateRotation, omegaRotation);
        return vertices;
    }

    static float computeGlowAlpha(float glowAlphaBase,
                                  float flameLevel,
                                  float edgeAlpha,
                                  float alphaScale) {
        float glowAlpha = Math.max(glowAlphaBase, flameLevel - 0.4f) * 0.75f * alphaScale;
        return Math.min(edgeAlpha, glowAlpha);
    }

    private static void transformStripVertex(float[] vertices, int offset,
                                             float x, float y,
                                             float posX, float posY,
                                             float angle,
                                             float rotation1,
                                             float rotation2,
                                             float translateX,
                                             float scaleX,
                                             float scaleY) {
        float transformedX = x * scaleX + translateX;
        float transformedY = y * scaleY;
        float[] rotated = rotate(transformedX, transformedY, rotation2);
        rotated = rotate(rotated[0], rotated[1], rotation1);
        rotated = rotate(rotated[0], rotated[1], angle);
        vertices[offset] = posX + rotated[0];
        vertices[offset + 1] = posY + rotated[1];
    }

    private static void transformCoreVertex(float[] vertices, int offset,
                                            float x, float y,
                                            float posX, float posY,
                                            float angle,
                                            float stateRotation,
                                            float omegaRotation) {
        float[] rotated = rotate(x, y, omegaRotation);
        rotated[0] *= 0.9f;
        rotated = rotate(rotated[0], rotated[1], stateRotation);
        rotated = rotate(rotated[0], rotated[1], angle);
        vertices[offset] = posX + rotated[0];
        vertices[offset + 1] = posY + rotated[1];
    }

    private static void emitStripVertex(int red, int green, int blue, int alpha,
                                        float texU, float texV,
                                        float[] vertices, int offset) {
        GL11.glColor4ub((byte) red, (byte) green, (byte) blue, (byte) alpha);
        GL11.glTexCoord2f(texU, texV);
        GL11.glVertex2f(vertices[offset], vertices[offset + 1]);
    }

    private static float[] rotate(float x, float y, float angleDegrees) {
        if (angleDegrees == 0.0f) {
            return new float[]{x, y};
        }
        float radians = angleDegrees * DEG_TO_RAD;
        float sin = (float) Math.sin(radians);
        float cos = (float) Math.cos(radians);
        return new float[]{x * cos - y * sin, x * sin + y * cos};
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static int clampColorComponent(int value) {
        return Math.max(0, Math.min(255, value));
    }

    static native void nativeRenderEngineStripBatch(float posX, float posY,
                                                    float angle,
                                                    float angularRotation,
                                                    float spreadRotation,
                                                    int passCount,
                                                    int layerCount,
                                                    float texUStart,
                                                    float texSpan,
                                                    float textureAdvance,
                                                    float innerLength,
                                                    float stripLength,
                                                    float stripWidth,
                                                    int red,
                                                    int green,
                                                    int blue,
                                                    float colorAlphaScale,
                                                    boolean exactAlphaPath);

    static native void nativeRenderEngineCorePass(float posX, float posY,
                                                  float angle,
                                                  float stateRotation,
                                                  float omegaRotation,
                                                  float stripLength,
                                                  float stripWidth,
                                                  int red,
                                                  int green,
                                                  int blue,
                                                  int alpha,
                                                  boolean exactAlphaPath);
}