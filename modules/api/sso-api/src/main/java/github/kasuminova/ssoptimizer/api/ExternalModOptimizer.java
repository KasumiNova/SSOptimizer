package github.kasuminova.ssoptimizer.api;

import java.util.Map;

/**
 * 面向「第三方模组性能优化」的优化集合接口。
 * <p>
 * 动机：针对第三方模组（由 Starsector 的 mod 类加载器加载，类名不在游戏混淆映射表内）的
 * 优化逻辑，以统一的「功能键 + 处理器映射」形状描述，与游戏自身优化的注册路径保持一致。
 * coremod 化后实现类源码收编进主模块，由 {@code SSOptimizerCorePlugin} 在 onLoad 阶段
 * 直接实例化注册（javaagent 时代的 ServiceLoader SPI 装配已移除，本接口不再是 SPI）。
 * <p>
 * 每个实现对应一个外部模组的优化集合。
 */
public interface ExternalModOptimizer {
    /**
     * 该优化集合的功能键，用于总开关。
     * <p>
     * 动机：与现有处理器一致地支持 {@code -Dssoptimizer.disable.<featureKey>=true} 紧急关闭。
     * {@code :app} 在注册前读取该系统属性；为 {@code true} 时跳过本实现的所有处理器。
     *
     * @return 稳定、短小、唯一的功能键（如 {@code "dcr"}）
     */
    String featureKey();

    /**
     * 该优化集合贡献的 ASM 处理器，键为目标类的 JVM 内部名（{@code /} 分隔）。
     * <p>
     * 动机：{@code :app} 的织入注册表是「类名 → 单个处理器」映射，同一目标类只能登记一个处理器。
     * 因此当多个优化（如压缩替换与序列化合并）针对同一类时，实现须在此用
     * {@link CompositeAsmClassProcessor} 自行组合为单个处理器后返回。返回的映射可依据系统属性
     * 子开关动态裁剪（例如某子优化被禁用时不纳入对应处理器）。
     *
     * @return 不可变映射；调用方将逐项注册到织入器，键冲突由调用方按后写覆盖处理（故同类须自行组合）
     */
    Map<String, AsmClassProcessor> processors();
}
