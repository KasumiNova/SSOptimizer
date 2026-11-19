package github.kasuminova.ssoptimizer.common.render.engine;

import com.fs.graphics.Sprite;
import com.fs.graphics.TextureObject;
import com.fs.graphics.util.GLListManager;
import com.fs.graphics.util.RenderStateUtils;
import com.fs.starfarer.combat.entities.Engine.EngineGlowType;
import com.fs.starfarer.loading.specs.EngineSlot;
import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.CollectedBatch;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.CoreGroup;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.CoreInstance;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.FrameInput;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.GlowGroup;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.GlowInstance;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.SlotInput;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.StripGroup;
import github.kasuminova.ssoptimizer.common.render.engine.EngineInstanceCollector.StripInstance;
import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import github.kasuminova.ssoptimizer.common.render.spritebatch.SpriteBatch;
import github.kasuminova.ssoptimizer.mixin.accessor.EngineOwnerAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.EngineSlotAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.EngineStateAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.ShipAccessor;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public final class EngineBatchImpl implements EngineBatch {
    private static final Logger LOGGER = Logger.getLogger(EngineBatchImpl.class);

    public static final String ENABLE_PROPERTY = "ssoptimizer.render.shipengine.enable";
    public static final String MODE_PROPERTY   = "ssoptimizer.render.shipengine.mode";
    public static final String STATS_PROPERTY  = "ssoptimizer.render.shipengine.stats";
    public static final String QUEUE_PROPERTY  = "ssoptimizer.render.shipengine.queue";

    private static final boolean DEBUG_ENGINE_STYLE =
            Boolean.parseBoolean(System.getProperty("ssoptimizer.debug.enginestyle", "false"));
    private static final java.util.Set<Object> DEBUG_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void debugLogTexture(final String label, final TextureObject texture) {
        if (!DEBUG_ENGINE_STYLE) {
            return;
        }
        if (texture == null) {
            if (DEBUG_LOGGED.add("null:" + label)) {
                LOGGER.warn("[SSOptimizer] engine batch texture NULL: " + label);
            }
            return;
        }
        if (DEBUG_LOGGED.add(System.identityHashCode(texture))) {
            LOGGER.info("[SSOptimizer] engine batch texture: " + label
                    + " path=" + texture.getTexturePath()
                    + " id=" + texture.getTextureId()
                    + " deferred=" + texture.isDeferredLoadingEnabled());
        }
    }

    private static final EngineBatchImpl INSTANCE = new EngineBatchImpl();

    private static final int VERTEX_VBO_CAPACITY   = 512 * 1024;
    private static final int INDEX_VBO_CAPACITY    = 128 * 1024;

    private final boolean enabled;
    private final boolean queueNativeEnabled;
    private final boolean statsEnabled;
    private final GlCapability.Mode requestedMode;

    private volatile GlCapability.Mode activeMode;

    private DynamicVbo      vertexVbo;
    private DynamicVbo      indexVbo;

    private ByteBuffer vertexScratch;
    private ByteBuffer indexScratch;
    private ByteBuffer flattenScratch;

    private int nativeVertexWriteOffset;
    private int nativeIndexWriteOffset;

    private boolean buildingListLogged;
    private volatile int displayListFallbacks;
    private int  framesSinceStatsLog;
    private boolean firstBatchLogged;

    private EngineBatchImpl() {
        String rawEnable = System.getProperty(ENABLE_PROPERTY, "true");
        boolean enable = !"false".equalsIgnoreCase(rawEnable.trim());
        this.enabled = enable;
        this.queueNativeEnabled = !"false".equalsIgnoreCase(
                System.getProperty(QUEUE_PROPERTY, "true").trim());
        if (enable && queueNativeEnabled && RenderThreadMode.isEnabled()) {
            LOGGER.info("[SSOptimizer] 渲染线程分离模式：引擎合批走渲染队列 native 命令通道");
        }
        this.statsEnabled = Boolean.parseBoolean(System.getProperty(STATS_PROPERTY, "false"));

        String rawMode = System.getProperty(MODE_PROPERTY, "vbo");
        GlCapability.Mode parsed = GlCapability.parseConfiguredMode(rawMode);
        if (parsed == null) {
            LOGGER.warn(String.format(
                    "[SSOptimizer] 无法识别的引擎合批模式 '%s'，使用默认 vbo", rawMode));
            parsed = GlCapability.Mode.VBO_BATCH;
        }
        this.requestedMode = parsed;
    }

    public static EngineBatchImpl getInstance() {
        return INSTANCE;
    }

    @Override
    public void render(Object engineObject, float alphaScale) {
        SpriteBatch.getInstance().flushPending();
        if (!enabled) {
            EngineRenderHelper.renderEngines(engineObject, alphaScale);
            return;
        }
        if (GLListManager.buildingList) {
            // display list 编译窗口不能使用 VBO 或 native 命令，相关调用不可编译。
            displayListFallbacks++;
            if (!buildingListLogged) {
                buildingListLogged = true;
                LOGGER.info("[SSOptimizer] 检测到 display list 编译，引擎合批退回立即模式");
            }
            EngineRenderHelper.renderEngines(engineObject, alphaScale);
            return;
        }
        if (RenderThreadMode.isEnabled()) {
            if (queueNativeEnabled && GlDispatch.canSubmitOwnedDraw() && NativeRuntime.isQueueGlReady()) {
                renderQueuedNative((EngineBridge) engineObject, alphaScale);
            } else {
                EngineRenderHelper.renderEngines(engineObject, alphaScale);
            }
            return;
        }
        if (activeMode == null) {
            activeMode = GlCapability.detectBest(requestedMode);
        }
        GlCapability.Mode mode = activeMode;
        if (mode == GlCapability.Mode.IMMEDIATE) {
            EngineRenderHelper.renderEngines(engineObject, alphaScale);
            return;
        }

        EngineBridge engine = (EngineBridge) engineObject;
        CollectedBatch batch = captureInputs(engine, alphaScale).collect();

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(770, 1);

        logBatchStats(mode, batch);

        if (batch.isEmpty()) {
            return;
        }
        flushVboBatch(batch);
    }

    private void renderQueuedNative(EngineBridge engine, float alphaScale) {
        EngineNativeRenderCommand command = captureInputs(engine, alphaScale);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(770, 1);
        if (command.hasSlots()) {
            GlDispatch.submit(command);
        }
    }

    void logBatchStats(GlCapability.Mode mode, CollectedBatch batch) {
        boolean first = !firstBatchLogged && !batch.isEmpty();
        boolean periodic = statsEnabled && ++framesSinceStatsLog >= 300;
        if (!first && !periodic) {
            return;
        }
        int stripCount = 0;
        for (StripGroup group : batch.strips) {
            stripCount += group.instances().size();
        }
        int coreCount = 0;
        for (CoreGroup group : batch.cores) {
            coreCount += group.instances().size();
        }
        int glowCount = 0;
        for (GlowGroup group : batch.glows) {
            glowCount += group.instances().size();
        }

        if (first) {
            firstBatchLogged = true;
            String sample = "无条带样本";
            if (!batch.strips.isEmpty() && !batch.strips.get(0).instances().isEmpty()) {
                StripInstance s = batch.strips.get(0).instances().get(0);
                sample = String.format(
                        "样本[pos=(%.1f,%.1f) len=%.2f halfW=%.2f alphaMid=%d tex=%d]",
                        s.posX(), s.posY(), s.stripLength(), s.halfWidth(), s.alphaMid(), s.textureId());
            }
            LOGGER.info(String.format(
                    "[SSOptimizer] 引擎合批首批次：mode=%s strips=%d cores=%d glows=%d %s",
                    mode, stripCount, coreCount, glowCount, sample));
        }

        if (periodic) {
            framesSinceStatsLog = 0;
            LOGGER.info(String.format(
                    "[SSOptimizer] 引擎合批统计：mode=%s strips=%d cores=%d glows=%d displayList回退=%d",
                    mode, stripCount, coreCount, glowCount, displayListFallbacks));
        }
    }

    private EngineNativeRenderCommand captureInputs(EngineBridge engine, float alphaScale) {
        EngineOwnerAccessor owner = (EngineOwnerAccessor) engine.ssoptimizer$getOwner();
        List<EngineSlot> engineSlots = owner.ssoptimizer$getEngineLocations();
        boolean fighter = owner.ssoptimizer$isFighter();

        boolean omegaMode = !engineSlots.isEmpty()
                && ((EngineSlotAccessor) engineSlots.get(0)).ssoptimizer$isOmegaMode();
        boolean withSpread = engineSlots.isEmpty()
                || ((EngineSlotAccessor) engineSlots.get(0)).ssoptimizer$isWithSpread();

        TextureObject primaryGlow = engine.ssoptimizer$getPrimaryGlowTexture();
        TextureObject secondaryGlow = engine.ssoptimizer$getSecondaryGlowTexture();
        TextureObject flame = engine.ssoptimizer$getFlameTexture();
        Sprite glowSprite = engine.ssoptimizer$getGlowSprite();
        TextureObject glowTexture = glowSprite == null ? null : glowSprite.getTexture();

        debugLogTexture("primaryGlow", primaryGlow);
        debugLogTexture("secondaryGlow", secondaryGlow);
        debugLogTexture("flame", flame);
        debugLogTexture("glowSpriteTex", glowTexture);

        FrameInput frame = new FrameInput(
                alphaScale,
                owner.ssoptimizer$getAngularVelocity(),
                omegaMode,
                withSpread,
                fighter,
                owner.ssoptimizer$isMissile(),
                engine.ssoptimizer$isBoostedFlameMode(),
                engine.ssoptimizer$getPrimaryFader().getBrightness(),
                engine.ssoptimizer$getSecondaryFader().getBrightness(),
                engine.ssoptimizer$getLengthShifter().isShifted()
                        || engine.ssoptimizer$getWidthShifter().isShifted()
                        || engine.ssoptimizer$getGlowShifter().isShifted(),
                engine.ssoptimizer$getLengthShifter().getCurr(),
                engine.ssoptimizer$getWidthShifter().getCurr(),
                engine.ssoptimizer$getGlowShifter().getCurr(),
                primaryGlow == null ? -1 : primaryGlow.getTextureId(),
                secondaryGlow == null ? -1 : secondaryGlow.getTextureId(),
                flame == null ? -1 : flame.getTextureId(),
                glowTexture == null ? -1 : glowTexture.getTextureId(),
                glowTexture == null ? 1.0f : glowTexture.getUScale(),
                glowTexture == null ? 1.0f : glowTexture.getVScale());

        List<SlotInput> slots = new ArrayList<>(engineSlots.size());
        float facing = owner.ssoptimizer$getFacing();
        for (EngineSlot slot : engineSlots) {
            EngineSlotAccessor slotAccessor = (EngineSlotAccessor) slot;
            EngineStateAccessor state = (EngineStateAccessor) engine.ssoptimizer$getState(slot);

            float flameLevel = state.ssoptimizer$getFlameLevel();
            float adjustedLevel = flameLevel;
            if (slotAccessor.ssoptimizer$isSystemActivated()
                    && engine.ssoptimizer$getOwner() instanceof ShipAccessor ship) {
                if (!engine.ssoptimizer$isSystemActivatedRenderingEnabled()) {
                    continue;
                }
                flameLevel *= engine.ssoptimizer$getLengthShifter()
                                       .getShiftProgress(ship.ssoptimizer$getSystem());
                if (flameLevel < 0.25f) {
                    adjustedLevel = 0.0f;
                } else {
                    adjustedLevel = (flameLevel - 0.25f) / 0.75f;
                }
            }
            if (flameLevel == 0.0f) {
                continue;
            }

            Color color = slotAccessor.ssoptimizer$getColor();
            if (engine.ssoptimizer$getColorShifter().isShifted()) {
                color = RenderStateUtils.blendColors(color,
                        engine.ssoptimizer$getColorShifter().getCurrForBase(color),
                        engine.ssoptimizer$getColorShiftFraction());
            }
            Color glowColor = null;
            Color alternate = slotAccessor.ssoptimizer$getGlowAlternateColor();
            if (alternate != null) {
                glowColor = alternate;
                if (engine.ssoptimizer$getColorShifter().isShifted()) {
                    glowColor = RenderStateUtils.blendColors(alternate,
                            engine.ssoptimizer$getColorShifter().getCurrForBase(alternate),
                            engine.ssoptimizer$getColorShiftFraction());
                }
            }

            Vector2f position = slotAccessor.ssoptimizer$computePosition(new Vector2f(), facing);
            slots.add(new SlotInput(
                    flameLevel, adjustedLevel,
                    state.ssoptimizer$getTexU(), state.ssoptimizer$getCoreRotation(),
                    state.ssoptimizer$getSpread(),
                    position.x, position.y,
                    slotAccessor.ssoptimizer$computeMidArcAngle(facing),
                    slotAccessor.ssoptimizer$getMaxSpread(),
                    slotAccessor.ssoptimizer$getLength(), slotAccessor.ssoptimizer$getWidth(),
                    snapshotColor(color), glowColor == null ? null : snapshotColor(glowColor),
                    slotAccessor.ssoptimizer$getGlowSizeMult(),
                    slotAccessor.ssoptimizer$getGlowType() == EngineGlowType.PRIMARY));
        }
        return new EngineNativeRenderCommand(frame, slots);
    }

    private static Color snapshotColor(Color color) {
        return color.getClass() == Color.class ? color
                : new Color(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha());
    }

    private void flushVboBatch(CollectedBatch batch) {
        if (vertexVbo == null) {
            vertexVbo = new DynamicVbo(GL15.GL_ARRAY_BUFFER, VERTEX_VBO_CAPACITY);
            indexVbo = new DynamicVbo(GL15.GL_ELEMENT_ARRAY_BUFFER, INDEX_VBO_CAPACITY);
        }
        if (NativeRuntime.isGlReady()) {
            flushVboBatchNative(batch);
            return;
        }

        int prevArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int prevElementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(770, 1);
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);

            for (StripGroup group : batch.strips) {
                flushExpanded(group.textureId(), group.instances().size(), 6,
                        (instance, out) -> EngineInstanceCollector.expandStripVertices(
                                (StripInstance) instance, out),
                        group.instances(), IndexWriter.STRIP);
            }
            for (CoreGroup group : batch.cores) {
                flushExpanded(group.textureId(), group.instances().size(), 4,
                        (instance, out) -> EngineInstanceCollector.expandCoreVertices(
                                (CoreInstance) instance, out),
                        group.instances(), IndexWriter.CORE);
            }
            for (GlowGroup group : batch.glows) {
                flushExpanded(group.textureId(), group.instances().size(), 4,
                        (instance, out) -> EngineInstanceCollector.expandGlowVertices(
                                (GlowInstance) instance, out),
                        group.instances(), IndexWriter.GLOW);
            }
        } finally {
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, prevElementBuffer);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuffer);
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
        }
    }

    private void flushVboBatchNative(CollectedBatch batch) {
        int requiredBytes = EngineInstanceCollector.flattenedBytes(batch);
        ByteBuffer commandBuffer = flattenScratch(requiredBytes);
        int commandCount = EngineInstanceCollector.flatten(batch, commandBuffer);

        // native 写入不扩容，Java 侧预检后须同步重置扩容后的偏移。
        if (vertexVbo.ensureCapacity(EngineInstanceCollector.expandedVertexBytes(batch))) {
            nativeVertexWriteOffset = 0;
        }
        if (indexVbo.ensureCapacity(EngineInstanceCollector.expandedIndexBytes(batch))) {
            nativeIndexWriteOffset = 0;
        }

        long packed = EngineBatchNative.nativeFlushBatch(commandBuffer, commandCount,
                vertexVbo.getBufferId(), vertexVbo.getCapacityBytes(), nativeVertexWriteOffset,
                indexVbo.getBufferId(), indexVbo.getCapacityBytes(), nativeIndexWriteOffset);
        nativeVertexWriteOffset = (int) (packed >>> 32);
        nativeIndexWriteOffset = (int) packed;
    }

    private ByteBuffer flattenScratch(int required) {
        if (flattenScratch == null || flattenScratch.capacity() < required) {
            int newCapacity = Math.max(64 * 1024, Integer.highestOneBit(required - 1) << 1);
            flattenScratch = ByteBuffer.allocateDirect(newCapacity).order(ByteOrder.nativeOrder());
        }
        return (ByteBuffer) flattenScratch.clear().limit(required);
    }

    private interface VertexExpander {
        void expand(Object instance, ByteBuffer out);
    }

    private enum IndexWriter {
        STRIP(12) {
            @Override
            void append(ByteBuffer out, int baseVertex) {
                EngineInstanceCollector.appendStripIndices(out, baseVertex);
            }
        },
        CORE(6) {
            @Override
            void append(ByteBuffer out, int baseVertex) {
                EngineInstanceCollector.appendCoreIndices(out, baseVertex);
            }
        },
        GLOW(6) {
            @Override
            void append(ByteBuffer out, int baseVertex) {
                EngineInstanceCollector.appendGlowIndices(out, baseVertex);
            }
        };

        final int indicesPerInstance;

        IndexWriter(int indicesPerInstance) {
            this.indicesPerInstance = indicesPerInstance;
        }

        abstract void append(ByteBuffer out, int baseVertex);
    }

    private void flushExpanded(int textureId, int instanceCount, int vertsPerInstance,
                               VertexExpander expander, List<?> instances, IndexWriter indexWriter) {
        int maxInstancesPerChunk = 65535 / vertsPerInstance;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);

        int offset = 0;
        while (offset < instanceCount) {
            int chunk = Math.min(instanceCount - offset, maxInstancesPerChunk);
            int vertexBytes = chunk * vertsPerInstance * EngineInstanceCollector.VERTEX_BYTES;
            int indexBytes = chunk * indexWriter.indicesPerInstance * 2;

            ByteBuffer vertices = vertexScratch(vertexBytes);
            ByteBuffer indices = indexScratch(indexBytes);
            for (int i = 0; i < chunk; i++) {
                expander.expand(instances.get(offset + i), vertices);
                indexWriter.append(indices, i * vertsPerInstance);
            }
            vertices.flip();
            indices.flip();

            int vertexBase = vertexVbo.write(vertices);
            int indexBase = indexVbo.write(indices);
            vertexVbo.bind();
            GL11.glVertexPointer(2, GL11.GL_FLOAT, EngineInstanceCollector.VERTEX_BYTES, (long) vertexBase);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, EngineInstanceCollector.VERTEX_BYTES, (long) vertexBase + 8);
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, EngineInstanceCollector.VERTEX_BYTES, (long) vertexBase + 16);
            indexVbo.bind();
            GL11.glDrawElements(GL11.GL_TRIANGLES, chunk * indexWriter.indicesPerInstance,
                    GL11.GL_UNSIGNED_SHORT, indexBase);

            offset += chunk;
        }
    }

    private ByteBuffer vertexScratch(int required) {
        if (vertexScratch == null || vertexScratch.capacity() < required) {
            int newCapacity = Math.max(128 * 1024, Integer.highestOneBit(required - 1) << 1);
            vertexScratch = ByteBuffer.allocateDirect(newCapacity).order(ByteOrder.nativeOrder());
        }
        return (ByteBuffer) vertexScratch.clear().limit(required);
    }

    private ByteBuffer indexScratch(int required) {
        if (indexScratch == null || indexScratch.capacity() < required) {
            int newCapacity = Math.max(32 * 1024, Integer.highestOneBit(required - 1) << 1);
            indexScratch = ByteBuffer.allocateDirect(newCapacity).order(ByteOrder.nativeOrder());
        }
        return (ByteBuffer) indexScratch.clear().limit(required);
    }
}
