package github.kasuminova.ssoptimizer.common.render.engine;

import com.fs.graphics.Sprite;
import com.fs.graphics.TextureObject;
import com.fs.graphics.util.GLListManager;
import com.fs.graphics.util.RenderStateUtils;
import com.fs.starfarer.combat.entities.Engine.EngineGlowType;
import com.fs.starfarer.loading.specs.EngineSlot;
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

/**
 * 引擎渲染合批实现：{@code Engine.render(float)} / {@code renderFighter(float)} 的替换路径。
 * <p>
 * 工作流程：收集（{@link EngineInstanceCollector}，纯 CPU）→ 按 阶段×纹理ID 分组 →
 * 按当前生效模式 flush（VBO_BATCH CPU 展开 / IMMEDIATE 回退
 * {@link EngineRenderHelper}）。每次 flush 在当前矩阵栈内进行（Ship push/pop 栈内被调，
 * 不缓存矩阵），结束后完整恢复 blend / 纹理绑定 / VBO 绑定 / client state。
 * <p>
 * 开关：
 * <ul>
 *   <li>{@code -Dssoptimizer.render.shipengine.enable}（默认 true，false 时退回立即模式等价路径）</li>
 *   <li>{@code -Dssoptimizer.render.shipengine.mode=vbo|immediate}（默认 vbo，
 *       按 GL 能力自动降级）</li>
 *   <li>{@code -Dssoptimizer.render.shipengine.stats=true}（默认 false，每 300 次渲染输出一次
 *       实例数与 display list 回退计数；首个非空批次无条件输出一次摘要）</li>
 * </ul>
 * display list 说明：舰船 display list 编译区间会包含引擎渲染调用，命中
 * {@link GLListManager#buildingList} 时退回立即模式等价路径（{@link EngineRenderHelper}，
 * 公式已与原版逐行校准），首次命中记一次日志。
 */
public final class EngineBatchImpl implements EngineBatch {
    private static final Logger LOGGER = Logger.getLogger(EngineBatchImpl.class);

    public static final String ENABLE_PROPERTY = "ssoptimizer.render.shipengine.enable";
    public static final String MODE_PROPERTY   = "ssoptimizer.render.shipengine.mode";
    public static final String STATS_PROPERTY  = "ssoptimizer.render.shipengine.stats";

    private static final EngineBatchImpl INSTANCE = new EngineBatchImpl();

    private static final int VERTEX_VBO_CAPACITY   = 512 * 1024;
    private static final int INDEX_VBO_CAPACITY    = 128 * 1024;

    private final boolean enabled;
    private final boolean statsEnabled;
    private final GlCapability.Mode requestedMode;

    /** 实际生效模式（渲染线程惰性探测后确定）。 */
    private volatile GlCapability.Mode activeMode;

    private DynamicVbo      vertexVbo;
    private DynamicVbo      indexVbo;

    private ByteBuffer vertexScratch;
    private ByteBuffer indexScratch;
    private ByteBuffer flattenScratch;

    /** native 环形写入偏移（仅 isGlReady 路径使用；DynamicVbo 内部偏移仅 Java 回退路径使用）。 */
    private int nativeVertexWriteOffset;
    private int nativeIndexWriteOffset;

    private boolean buildingListLogged;
    private int  displayListFallbacks;
    private int  framesSinceStatsLog;
    private boolean firstBatchLogged;

    private EngineBatchImpl() {
        String rawEnable = System.getProperty(ENABLE_PROPERTY, "true");
        this.enabled = !"false".equalsIgnoreCase(rawEnable.trim());
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
        // Sprite 合批顺序边界：引擎（非 sprite 绘制）前 flush 已累积批次
        SpriteBatch.getInstance().flushPending();
        if (!enabled) {
            EngineRenderHelper.renderEngines(engineObject, alphaScale);
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
        if (GLListManager.buildingList) {
            // display list 编译区间内禁止使用 VBO/着色器路径（glBufferSubData 等不会被记录且语义错乱）
            displayListFallbacks++;
            if (!buildingListLogged) {
                buildingListLogged = true;
                LOGGER.info("[SSOptimizer] 检测到 display list 编译，引擎合批退回立即模式");
            }
            EngineRenderHelper.renderEngines(engineObject, alphaScale);
            return;
        }

        EngineBridge engine = (EngineBridge) engineObject;
        CollectedBatch batch = gather(engine, alphaScale);

        // 原版无论是否有可渲染槽都会执行这三个状态设置且不恢复，逐条复刻以保持一致
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(770, 1);

        logBatchStats(mode, batch);

        if (batch.isEmpty()) {
            return;
        }
        flushVboBatch(batch);
    }

    // ---------------------------------------------------------------------
    // 诊断：首批次一次性摘要（无条件 INFO）+ 周期统计（stats 开关）
    // ---------------------------------------------------------------------

    private void logBatchStats(GlCapability.Mode mode, CollectedBatch batch) {
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

        if (!firstBatchLogged && !batch.isEmpty()) {
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

        if (statsEnabled && ++framesSinceStatsLog >= 300) {
            framesSinceStatsLog = 0;
            LOGGER.info(String.format(
                    "[SSOptimizer] 引擎合批统计：mode=%s strips=%d cores=%d glows=%d displayList回退=%d",
                    mode, stripCount, coreCount, glowCount, displayListFallbacks));
        }
    }

    // ---------------------------------------------------------------------
    // 收集：从游戏对象读取参数（accessor），公式全部在 EngineInstanceCollector
    // ---------------------------------------------------------------------

    private CollectedBatch gather(EngineBridge engine, float alphaScale) {
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
                    color, glowColor,
                    slotAccessor.ssoptimizer$getGlowSizeMult(),
                    slotAccessor.ssoptimizer$getGlowType() == EngineGlowType.PRIMARY));
        }

        CollectedBatch batch = new CollectedBatch();
        EngineInstanceCollector.collect(frame, slots, batch);
        return batch;
    }

    // ---------------------------------------------------------------------
    // VBO_BATCH flush：CPU 展开三角形写入环形 VBO，固定管线绘制
    // ---------------------------------------------------------------------

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

    /**
     * native flush：扁平化批次后单次 JNI 完成顶点展开、环形 VBO 写入与逐组绘制。
     * VBO 绑定全程在 native 内恢复，不经 LWJGL（StateTracker 无失配风险）。
     */
    private void flushVboBatchNative(CollectedBatch batch) {
        int requiredBytes = EngineInstanceCollector.flattenedBytes(batch);
        ByteBuffer commandBuffer = flattenScratch(requiredBytes);
        int commandCount = EngineInstanceCollector.flatten(batch, commandBuffer);

        // native 环形写入不扩容，容量预检在 Java 侧完成（扩容后 native 偏移同步清零）
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

    /** 展开一组实例并绘制；实例过多时按 16 位索引上限分块。 */
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
