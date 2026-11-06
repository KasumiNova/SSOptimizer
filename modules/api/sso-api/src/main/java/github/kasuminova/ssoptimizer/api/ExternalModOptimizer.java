package github.kasuminova.ssoptimizer.api;

import java.util.Map;

/**
 * 描述一个第三方模组的 ASM 优化集合，向 coremod 统一暴露功能开关与目标类处理器。
 * <p>
 * 每个实现只负责一个外部模组。方法在 coremod 加载阶段同步调用，不得要求游戏运行时已经初始化。
 */
public interface ExternalModOptimizer {
    /**
     * 返回用于总开关的稳定功能键，调用方可通过
     * {@code -Dssoptimizer.disable.<featureKey>=true} 跳过整个优化集合。
     *
     * @return 短小且在全部优化集合中唯一的功能键
     */
    String featureKey();

    /**
     * 返回本集合贡献的 ASM 处理器，键为目标类的 JVM 内部名。
     * 同一目标类的多个处理器必须先通过 {@link CompositeAsmClassProcessor} 组合；
     * 实现可依据子开关裁剪映射内容。
     *
     * @return 不可变映射，键和值均不为 {@code null}；调用方逐项注册，键冲突按后写覆盖处理
     */
    Map<String, AsmClassProcessor> processors();
}
