package github.kasuminova.ssoptimizer.api;

/**
 * 对单个完整类文件执行字节码变换，将处理逻辑与织入注册入口解耦。
 * <p>
 * 织入器在类加载线程同步调用实现；实现不得依赖游戏主线程或渲染线程上下文。
 */
@FunctionalInterface
public interface AsmClassProcessor {
    /**
     * 处理一个完整类文件，返回完整结果而非差异片段。
     *
     * @param classfileBuffer 原始类文件字节码
     * @return 处理后的完整类文件字节码；未修改时返回 {@code null}
     * @throws RuntimeException 无法完成变换时抛出，由织入调用方处理
     */
    byte[] process(byte[] classfileBuffer);
}
