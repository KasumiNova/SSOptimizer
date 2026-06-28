package github.kasuminova.ssoptimizer.modopt.dcr;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.api.ExternalModOptimizer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DetailedCombatResults（DCR）模组的性能优化集合。经 {@code META-INF/services} 注册，由
 * {@code SSOptimizerAgent} 在 agent 启动阶段通过 {@link java.util.ServiceLoader} 发现并注册。
 * <p>
 * 贡献三个处理器，目标类互不相同，故无需 {@code CompositeAsmClassProcessor}：
 * <ul>
 *   <li>L1：{@link DcrBatchSaveSynthProcessor}（SerializationManager 合成 collect/flush）
 *       + {@link DcrOnGameLoadProcessor}（onGameLoad redirect/inject）——消除读档 O(N²) 重写。</li>
 *   <li>L2：{@link DcrCompressionProcessor}（CompressionUtil 压缩内核 Deflate→Zstd）——默认开，
 *       {@code -Dssoptimizer.disable.dcrzstd=true} 时不纳入。</li>
 * </ul>
 * 总开关：{@code -Dssoptimizer.disable.dcr=true}（由调用方按 {@link #featureKey()} 判断）。
 */
public final class DcrModOptimizer implements ExternalModOptimizer {

    /** 功能键，对应 {@code -Dssoptimizer.disable.dcr} 总开关。 */
    public static final String FEATURE_KEY = "dcr";

    /** L2 Zstd 压缩子开关：为 {@code true} 时排除 {@link DcrCompressionProcessor}（保留 DCR 原生 Deflater）。 */
    public static final String DISABLE_ZSTD_PROPERTY = "ssoptimizer.disable.dcrzstd";

    @Override
    public String featureKey() {
        return FEATURE_KEY;
    }

    @Override
    public Map<String, AsmClassProcessor> processors() {
        final Map<String, AsmClassProcessor> processors = new LinkedHashMap<>();
        processors.put(DcrBatchSaveSynthProcessor.TARGET_CLASS, new DcrBatchSaveSynthProcessor());
        processors.put(DcrOnGameLoadProcessor.TARGET_CLASS, new DcrOnGameLoadProcessor());
        if (!Boolean.getBoolean(DISABLE_ZSTD_PROPERTY)) {
            processors.put(DcrCompressionProcessor.TARGET_CLASS, new DcrCompressionProcessor());
        }
        return processors;
    }
}
