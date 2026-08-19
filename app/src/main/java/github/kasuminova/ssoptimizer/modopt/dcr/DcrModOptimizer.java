package github.kasuminova.ssoptimizer.modopt.dcr;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.api.ExternalModOptimizer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DetailedCombatResults（DCR）模组的性能优化集合。源码已收编进 coremod 主模块，
 * 由 {@code SSOptimizerCorePlugin} 在 coremod {@code onLoad} 阶段直接实例化并注册
 * （javaagent 时代的 ServiceLoader SPI 装配已随 agent 通道一并移除）。
 * <p>
 * 贡献两个处理器，目标类互不相同，故无需 {@code CompositeAsmClassProcessor}：
 * <ul>
 *   <li>L1：{@link DcrBatchSaveSynthProcessor}（SerializationManager 合成 collect/flush）
 *       + {@link DcrOnGameLoadProcessor}（onGameLoad redirect/inject）——消除读档 O(N²) 重写。</li>
 *   <li>L2 压缩内核（CompressionUtil Deflate→Zstd）已迁移为
 *       {@code mixin.modopt.dcr.DcrCompressionUtilMixin}，不再经本集合注册。</li>
 * </ul>
 * 总开关：{@code -Dssoptimizer.disable.dcr=true}（由调用方按 {@link #featureKey()} 判断）。
 */
public final class DcrModOptimizer implements ExternalModOptimizer {

    /** 功能键，对应 {@code -Dssoptimizer.disable.dcr} 总开关。 */
    public static final String FEATURE_KEY = "dcr";

    @Override
    public String featureKey() {
        return FEATURE_KEY;
    }

    @Override
    public Map<String, AsmClassProcessor> processors() {
        final Map<String, AsmClassProcessor> processors = new LinkedHashMap<>();
        processors.put(DcrBatchSaveSynthProcessor.TARGET_CLASS, new DcrBatchSaveSynthProcessor());
        processors.put(DcrOnGameLoadProcessor.TARGET_CLASS, new DcrOnGameLoadProcessor());
        return processors;
    }
}
