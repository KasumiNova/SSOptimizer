package github.kasuminova.ssoptimizer.mapping;

/**
 * 映射方向。
 * <p>
 * 用于统一描述“混淆 → 可读”和“可读 → 混淆”两条转换链路，供编译期 remap、
 * 运行时 remap 以及 reobf 产物复用同一套查找逻辑。
 */
public enum MappingDirection {
    /** 从混淆命名空间映射到可读命名空间。 */
    OBFUSCATED_TO_NAMED,
    /** 从可读命名空间映射回混淆命名空间。 */
    NAMED_TO_OBFUSCATED
}
