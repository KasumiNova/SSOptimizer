package github.kasuminova.ssoptimizer.bootstrap;

/**
 * ASM 字节码处理器的核心抽象接口。
 * <p>
 * 函数式接口，接收原始类文件字节码，返回处理后的字节码。
 * 若处理器未修改字节码，应返回 {@code null} 表示无变更。
 */
@FunctionalInterface
public interface AsmClassProcessor {
    /**
     * 处理给定类的字节码。
     *
     * @param classfileBuffer 原始类文件字节码
     * @return 处理后的字节码；若未修改则返回 {@code null}
     */
    byte[] process(byte[] classfileBuffer);
}