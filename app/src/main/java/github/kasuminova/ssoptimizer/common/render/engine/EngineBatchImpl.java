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
import github.kasuminova.ssoptimizer.mixin.accessor.EngineOwnerAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.EngineSlotAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.EngineStateAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.ShipAccessor;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 引擎渲染合批实现：{@code Engine.render(float)} / {@code renderFighter(float)} 的替换路径。
 * <p>
 * 工作流程：收集（{@link EngineInstanceCollector}，纯 CPU）→ 按 阶段×纹理ID 分组 →
 * 按当前生效模式 flush（INSTANCED 着色器展开 / VBO_BATCH CPU 展开 / IMMEDIATE 回退
 * {@link EngineRenderHelper}）。每次 flush 在当前矩阵栈内进行（Ship push/pop 栈内被调，
 * 不缓存矩阵），结束后完整恢复 blend / 纹理绑定 / VBO 绑定 / client state / 着色器程序。
 * <p>
 * 开关：
 * <ul>
 *   <li>{@code -Dssoptimizer.render.shipengine.enable}（默认 true，false 时退回立即模式等价路径）</li>
 *   <li>{@code -Dssoptimizer.render.shipengine.mode=instanced|vbo|immediate}（默认 instanced，
 *       按 GL 能力自动降级；着色器编译失败记 ERROR 并运行时降级）</li>
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

    private static final int INSTANCE_VBO_CAPACITY = 256 * 1024;
    private static final int VERTEX_VBO_CAPACITY   = 512 * 1024;
    private static final int INDEX_VBO_CAPACITY    = 128 * 1024;
    private static final int MAX_INSTANCE_ATTRIBS  = 5;

    private final boolean enabled;
    private final boolean statsEnabled;
    private final GlCapability.Mode requestedMode;

    /** 实际生效模式（渲染线程惰性探测后确定）；着色器失败时可运行时降级。 */
    private volatile GlCapability.Mode activeMode;

    private DynamicVbo      instanceVbo;
    private DynamicVbo      vertexVbo;
    private DynamicVbo      indexVbo;
    private EngineGlProgram program;

    private ByteBuffer vertexScratch;
    private ByteBuffer indexScratch;
    private ByteBuffer instanceScratch;

    private boolean buildingListLogged;
    private int  displayListFallbacks;
    private int  framesSinceStatsLog;
    private boolean firstBatchLogged;

    private EngineBatchImpl() {
        String rawEnable = System.getProperty(ENABLE_PROPERTY, "true");
        this.enabled = !"false".equalsIgnoreCase(rawEnable.trim());
        this.statsEnabled = Boolean.parseBoolean(System.getProperty(STATS_PROPERTY, "false"));

        String rawMode = System.getProperty(MODE_PROPERTY, "instanced");
        GlCapability.Mode parsed = GlCapability.parseConfiguredMode(rawMode);
        if (parsed == null) {
            LOGGER.warn(String.format(
                    "[SSOptimizer] 无法识别的引擎合批模式 '%s'，使用默认 instanced", rawMode));
            parsed = GlCapability.Mode.INSTANCED;
        }
        this.requestedMode = parsed;
    }

    public static EngineBatchImpl getInstance() {
        return INSTANCE;
    }

    @Override
    public void render(Object engineObject, float alphaScale) {
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
        if (mode == GlCapability.Mode.INSTANCED) {
            if (!flushInstanced(batch)) {
                // 着色器编译失败（ERROR 已记）：运行时降级到 VBO_BATCH
                LOGGER.warn("[SSOptimizer] 引擎合批 INSTANCED 模式不可用，降级为 VBO_BATCH");
                activeMode = GlCapability.Mode.VBO_BATCH;
                flushVboBatch(batch);
            }
        } else {
            flushVboBatch(batch);
        }
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
    // 诊断：INSTANCED 首绘 GL 状态一次性转储（定位"绘制执行但不可见"用）
    // ---------------------------------------------------------------------

    private boolean instancedDiagDumped;

    /** 首个条带组绘制后转储一次关键 GL 状态与错误码（无条件 INFO，只触发一次）。 */
    private void dumpInstancedDiagOnce(int drawnInstances, int textureId) {
        if (instancedDiagDumped) {
            return;
        }
        instancedDiagDumped = true;

        int err = GL11.glGetError();
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0;
        int textureBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int currentProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        int framebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

        FloatBuffer mv = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, mv);
        FloatBuffer pj = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, pj);

        LOGGER.info(String.format(
                "[SSOptimizer] INSTANCED 首绘诊断：drawn=%d tex=%d err=%d activeTexUnit=%d texBinding=%d "
                        + "program=%d fbo=%d matrixMode=%d clientArrays[vtx=%b tex=%b col=%b] "
                        + "tests[depth=%b stencil=%b alpha=%b func=%d] blend=%b "
                        + "mv[0]=%.3f mv[5]=%.3f mv[12]=%.3f mv[13]=%.3f pj[0]=%.6f pj[5]=%.6f pj[10]=%.3f",
                drawnInstances, textureId, err, activeTexture, textureBinding,
                currentProgram, framebuffer, matrixMode,
                GL11.glGetBoolean(GL11.GL_VERTEX_ARRAY),
                GL11.glGetBoolean(GL11.GL_TEXTURE_COORD_ARRAY),
                GL11.glGetBoolean(GL11.GL_COLOR_ARRAY),
                GL11.glGetBoolean(GL11.GL_DEPTH_TEST),
                GL11.glGetBoolean(GL11.GL_STENCIL_TEST),
                GL11.glGetBoolean(GL11.GL_ALPHA_TEST),
                GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC),
                GL11.glGetBoolean(GL11.GL_BLEND),
                mv.get(0), mv.get(5), mv.get(12), mv.get(13),
                pj.get(0), pj.get(5), pj.get(10)));
    }

    // ---------------------------------------------------------------------
    // INSTANCED flush：实例属性写入环形 VBO，着色器内展开几何
    // ---------------------------------------------------------------------

    /** @return false 表示着色器程序不可用（调用方降级） */
    private boolean flushInstanced(CollectedBatch batch) {
        if (program == null) {
            program = EngineGlProgram.create();
            if (program == null) {
                return false;
            }
        }
        if (instanceVbo == null) {
            instanceVbo = new DynamicVbo(GL15.GL_ARRAY_BUFFER, INSTANCE_VBO_CAPACITY);
        }

        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT);
        try {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(770, 1);

            for (StripGroup group : batch.strips) {
                int count = group.instances().size();
                FloatBuffer view = instanceView(count * EngineInstanceCollector.STRIP_INSTANCE_FLOATS);
                for (StripInstance instance : group.instances()) {
                    EngineInstanceCollector.packStripInstance(instance, view);
                }
                int base = uploadInstances(view);

                program.useStrip();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, group.textureId());
                bindInstanceAttribs(5, EngineInstanceCollector.STRIP_INSTANCE_FLOATS * 4, base);
                GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 12, count);
                dumpInstancedDiagOnce(count, group.textureId());
            }

            for (CoreGroup group : batch.cores) {
                int count = group.instances().size();
                FloatBuffer view = instanceView(count * EngineInstanceCollector.CORE_INSTANCE_FLOATS);
                for (CoreInstance instance : group.instances()) {
                    EngineInstanceCollector.packCoreInstance(instance, view);
                }
                int base = uploadInstances(view);

                program.useCore();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, group.textureId());
                bindInstanceAttribs(3, EngineInstanceCollector.CORE_INSTANCE_FLOATS * 4, base);
                GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 6, count);
            }

            for (GlowGroup group : batch.glows) {
                int count = group.instances().size();
                FloatBuffer view = instanceView(count * EngineInstanceCollector.GLOW_INSTANCE_FLOATS);
                for (GlowInstance instance : group.instances()) {
                    EngineInstanceCollector.packGlowInstance(instance, view);
                }
                int base = uploadInstances(view);

                program.useGlow();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, group.textureId());
                bindInstanceAttribs(4, EngineInstanceCollector.GLOW_INSTANCE_FLOATS * 4, base);
                GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 6, count);
            }
        } finally {
            for (int loc = 0; loc < MAX_INSTANCE_ATTRIBS; loc++) {
                GL33.glVertexAttribDivisor(loc, 0);
                GL20.glDisableVertexAttribArray(loc);
            }
            GL20.glUseProgram(prevProgram);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuffer);
            GL11.glPopAttrib();
        }
        return true;
    }

    /** 绑定 per-instance 属性：前 count 个位置设为 divisor=1 并指向实例数据，其余位置关闭，
     * 避免前一阶段（条带 5 属性）残留的 attrib 数组被后续程序误读。 */
    private void bindInstanceAttribs(int count, int stride, int base) {
        for (int loc = 0; loc < MAX_INSTANCE_ATTRIBS; loc++) {
            if (loc < count) {
                GL20.glEnableVertexAttribArray(loc);
                GL20.glVertexAttribPointer(loc, 4, GL11.GL_FLOAT, false, stride, base + loc * 16L);
                GL33.glVertexAttribDivisor(loc, 1);
            } else {
                GL33.glVertexAttribDivisor(loc, 0);
                GL20.glDisableVertexAttribArray(loc);
            }
        }
    }

    /** 上传一组实例数据到环形 VBO，返回起始字节偏移（VBO 保持绑定供属性指针使用）。 */
    private int uploadInstances(FloatBuffer view) {
        view.flip();
        ByteBuffer bytes = (ByteBuffer) instanceScratch.duplicate().clear()
                                                            .limit(view.remaining() * 4);
        bytes.asFloatBuffer().put(view);
        int base = instanceVbo.write(bytes);
        instanceVbo.bind();
        return base;
    }

    private FloatBuffer instanceView(int floatCount) {
        int capacity = floatCount * 4;
        if (instanceScratch == null || instanceScratch.capacity() < capacity) {
            int newCapacity = Math.max(64 * 1024, Integer.highestOneBit(capacity - 1) << 1);
            instanceScratch = ByteBuffer.allocateDirect(newCapacity).order(ByteOrder.nativeOrder());
        }
        return (FloatBuffer) instanceScratch.asFloatBuffer().clear().limit(floatCount);
    }

    // ---------------------------------------------------------------------
    // VBO_BATCH flush：CPU 展开三角形写入环形 VBO，固定管线绘制
    // ---------------------------------------------------------------------

    private void flushVboBatch(CollectedBatch batch) {
        if (vertexVbo == null) {
            vertexVbo = new DynamicVbo(GL15.GL_ARRAY_BUFFER, VERTEX_VBO_CAPACITY);
            indexVbo = new DynamicVbo(GL15.GL_ELEMENT_ARRAY_BUFFER, INDEX_VBO_CAPACITY);
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
