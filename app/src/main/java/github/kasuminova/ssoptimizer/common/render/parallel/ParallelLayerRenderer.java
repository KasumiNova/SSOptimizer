package github.kasuminova.ssoptimizer.common.render.parallel;

import com.fs.graphics.LayeredRenderer;
import com.fs.starfarer.combat.entities.CustomCombatEntity;
import github.kasuminova.ssoptimizer.bridge.opengl.ParallelRecording;
import github.kasuminova.ssoptimizer.common.combat.ai.AiParallelExecutor;
import github.kasuminova.ssoptimizer.common.combat.ai.ParallelAiDispatcher;
import github.kasuminova.ssoptimizer.common.render.queue.RenderSegment;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import github.kasuminova.ssoptimizer.mixin.accessor.LayeredRendererAccessor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

/**
 * 实体级并行录制编排器：拦截 {@code CombatEngine.render(boolean)} 内
 * {@code LayeredRenderer.renderExcluding} 的 17 层遍历（经
 * {@code CombatEngineParallelRenderMixin} @Redirect），把每层渲染物列表
 * 分片派发到 AI 并行池 worker 录制进各自的{@link RenderSegment}，层间帧内
 * 屏障保层序；回放侧面对的仍是按段登记序拼接的有序帧（编排不变量见
 * docs/design/render-parallel-audit.md §6）。
 * <p>
 * 编排规则：
 * <ul>
 *   <li>串行回退（整体直通原版遍历）：开关关闭、渲染线程模式关闭、执行池
 *       不可用、检测到 BoxUtil（其 aux 线程 GL 协议与并行录制不兼容，
 *       docs/design/boxutil-parallel-integration.md 方案 A 落地前维持回退）；</li>
 *   <li>层内分流：{@link CustomCombatEntity}（模组渲染代理，行为不可枚举）
 *       钉入层尾串行段，由主线程在屏障后按原相对序渲染；</li>
 *   <li>分片约束：舰载机与母舰同段（{@code Ship.renderShadow} 跨实体写
 *       {@code launchingShip.clipToShip}）——分组键为母舰（无母舰为自身），
 *       组内实体保持列表相对序；</li>
 *   <li>worker 段任务异常直接随 awaitAll 传播（fail-fast）：段内已录制的
 *       部分命令无法安全丢弃重录，与 StallDetector 的拒绝静默放行哲学一致。</li>
 * </ul>
 */
public final class ParallelLayerRenderer {
    /** 整体开关：{@code -Dssoptimizer.render.parallel=false} 回退原版串行遍历。 */
    public static final String ENABLED_PROPERTY = "ssoptimizer.render.parallel";
    /** 层内可并行渲染物少于此数时整层串行（分片开销大于收益）。 */
    static final int MIN_PARALLEL_SIZE = 4;

    private static final Logger LOGGER = Logger.getLogger(ParallelLayerRenderer.class);
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));
    /** BoxUtil 探测：类存在即判定不兼容（一次性，随日志告知自动回退）。 */
    private static final boolean BOXUTIL_PRESENT = probeBoxUtil();
    private static volatile boolean fallbackLogged = false;

    private ParallelLayerRenderer() {
    }

    /**
     * {@code CombatEngine.render} 内 renderExcluding 的重定向入口：
     * 满足并行条件时按层分片并行录制，否则直通原版遍历。
     *
     * @param renderer 战斗引擎的分层渲染器
     * @param viewport 当前视口
     * @param excluded 本轮排除的层（ABOVE_PARTICLES×2 + JUST_BELOW_WIDGETS）
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void renderExcluding(LayeredRenderer renderer, Object viewport, Enum[] excluded) {
        AiParallelExecutor executor = ENABLED ? ParallelAiDispatcher.executor() : null;
        if (executor == null || !RenderThreadMode.isEnabled()) {
            logFallbackOnce("实体级并行录制未启用（开关=" + ENABLED
                    + "，执行池=" + (executor != null) + "，渲染线程模式=" + RenderThreadMode.isEnabled()
                    + "），使用原版串行遍历");
            renderer.renderExcluding(viewport, excluded);
            return;
        }
        if (BOXUTIL_PRESENT) {
            logFallbackOnce("检测到 BoxUtil，实体级并行录制自动回退为串行"
                    + "（aux 线程 GL 协议不兼容，方案落地前维持回退）");
            renderer.renderExcluding(viewport, excluded);
            return;
        }

        LayeredRendererAccessor accessor = (LayeredRendererAccessor) renderer;
        logEngagedOnce(executor);
        Map<?, ?> layers = accessor.ssoptimizer$getLayers();
        Set<Enum> excludedSet = new HashSet<>(List.of(excluded));
        for (Object layerObj : EnumSet.allOf((Class) accessor.ssoptimizer$getLayerEnumClass())) {
            Enum layer = (Enum) layerObj;
            if (excludedSet.contains(layer)) {
                continue;
            }
            List<Object> renderables = (List<Object>) layers.get(layer);
            if (renderables == null || renderables.isEmpty()) {
                continue;
            }
            renderLayer(executor, renderables, layer, viewport);
        }
    }

    /** 单层编排：分流 → 分片 → worker 并行录制 → 屏障 → 层尾串行段。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderLayer(AiParallelExecutor executor, List<Object> renderables,
                                    Enum layer, Object viewport) {
        List<Object> parallel = new ArrayList<>(renderables.size());
        List<Object> serialTail = new ArrayList<>(0);
        for (Object renderable : renderables) {
            if (renderable instanceof CustomCombatEntity) {
                serialTail.add(renderable);
            } else {
                parallel.add(renderable);
            }
        }
        if (parallel.size() < MIN_PARALLEL_SIZE) {
            // 整层串行：当前串行段内按原序录制（模组代理保持层内原位）
            for (Object renderable : renderables) {
                ((com.fs.graphics.LayeredRenderable) renderable).render(layer, viewport);
            }
            return;
        }

        int shardCount = Math.min(executor.threadCount(), parallel.size());
        List<List<Object>> shards = shardByGroup(parallel, shardCount);

        RenderSegment[] segments = ParallelRecording.reserveSegments(shardCount);
        // 段任务异常收集：渲染录制非幂等（命令已部分入段），不能走 AI 池的
        // 串行重跑降级——失败任务在段内吞获、任务体正常返回，屏障后统一
        // fail-fast 抛给主线程。段内阻塞 GL 调用等缺口必须修复调用点，
        // 重跑只会重复录制并再次失败。
        Throwable[] taskErrors = new Throwable[shardCount];
        for (int w = 0; w < shardCount; w++) {
            List<Object> shard = shards.get(w);
            if (shard.isEmpty()) {
                continue;
            }
            RenderSegment segment = segments[w];
            int shardIndex = w;
            executor.submit(() -> {
                ParallelRecording.bindSegment(segment);
                try {
                    for (Object renderable : shard) {
                        ((com.fs.graphics.LayeredRenderable) renderable).render(layer, viewport);
                    }
                } catch (Throwable t) {
                    taskErrors[shardIndex] = t;
                } finally {
                    ParallelRecording.unbindSegment();
                }
            }, null);
        }
        executor.awaitAll();
        Throwable firstError = null;
        int errorCount = 0;
        for (Throwable t : taskErrors) {
            if (t == null) {
                continue;
            }
            errorCount++;
            if (firstError == null) {
                firstError = t;
            }
        }
        if (firstError != null) {
            RuntimeException propagated = new RuntimeException(
                    "[SSOptimizer] 并行录制段任务失败（" + errorCount + " 个分片，不重跑：渲染录制非幂等，"
                            + "段内失败属实现缺口，须修复调用点）", firstError);
            for (Throwable t : taskErrors) {
                if (t != null && t != firstError) {
                    propagated.addSuppressed(t);
                }
            }
            throw propagated;
        }

        // 层尾串行段：模组代理渲染物在屏障后由主线程按原相对序录制
        ParallelRecording.openNextSerialSegment();
        for (Object renderable : serialTail) {
            ((com.fs.graphics.LayeredRenderable) renderable).render(layer, viewport);
        }
    }

    /** 分片分组键：舰载机归母舰所在段，其余以自身为键。 */
    private static Object groupKey(Object renderable) {
        if (renderable instanceof LaunchingShipLink) {
            Object mothership = ((LaunchingShipLink) renderable).ssoptimizer$getLaunchingShip();
            if (mothership != null) {
                return mothership;
            }
        }
        return renderable;
    }

    /**
     * 分组轮询分片（包内可见供单测）：同组（舰载机→母舰）进同段，
     * 组内与跨组均保持列表相对序；返回 shardCount 个分片（前段可能为空，
     * 由调用方跳过空分片的任务派发）。
     */
    static List<List<Object>> shardByGroup(List<Object> parallel, int shardCount) {
        List<List<Object>> shards = new ArrayList<>(shardCount);
        for (int i = 0; i < shardCount; i++) {
            shards.add(new ArrayList<>());
        }
        Map<Object, Integer> groupShard = new IdentityHashMap<>();
        int cursor = 0;
        for (Object renderable : parallel) {
            Object key = groupKey(renderable);
            Integer shard = groupShard.get(key);
            if (shard == null) {
                shard = cursor;
                cursor = (cursor + 1) % shardCount;
                groupShard.put(key, shard);
            }
            shards.get(shard).add(renderable);
        }
        return shards;
    }

    private static boolean probeBoxUtil() {
        try {
            Class.forName("org.boxutil.BoxUtilModPlugin", false,
                    ParallelLayerRenderer.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void logFallbackOnce(String reason) {
        if (!fallbackLogged) {
            fallbackLogged = true;
            LOGGER.info("[SSOptimizer] " + reason);
        }
    }

    private static volatile boolean engagedLogged = false;

    private static void logEngagedOnce(AiParallelExecutor executor) {
        if (!engagedLogged) {
            engagedLogged = true;
            LOGGER.info("[SSOptimizer] 实体级并行录制已启用（worker=" + executor.threadCount()
                    + "，BoxUtil 探测=" + BOXUTIL_PRESENT + "）");
        }
    }
}
