package github.kasuminova.ssoptimizer.api;

import java.util.Map;

/**
 * 面向「第三方模组性能优化」的服务提供者接口（SPI）。
 * <p>
 * 动机：SSOptimizer 通过 {@code -javaagent} 在运行时对游戏类做 ASM 注入。针对第三方模组
 * （由 Starsector 的 mod 类加载器加载，类名不在游戏混淆映射表内）的优化逻辑，应与游戏自身
 * 优化（{@code :app}）解耦、独立存放于 {@code :mod-optimizations} 模块。本接口即解耦边界：
 * {@code :app} 在 agent 启动阶段经 {@link java.util.ServiceLoader} 发现并注册各实现，
 * 而无需在编译期依赖任何具体模组的优化代码。
 * <p>
 * 每个实现对应一个外部模组的优化集合。实现类须在
 * {@code META-INF/services/github.kasuminova.ssoptimizer.api.ExternalModOptimizer} 中声明。
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
