package github.kasuminova.ssoptimizer.mapping;

/**
 * 映射查找失败时抛出的可读异常。
 * <p>
 * 与其让调用方在空值里摸黑，不如直接把缺失项名称讲清楚。
 */
public final class MappingLookupException extends RuntimeException {

    /**
     * 创建一个带消息的映射查找异常。
     *
     * @param message 异常消息
     */
    public MappingLookupException(String message) {
        super(message);
    }

    /**
     * 创建一个带消息和根因的映射查找异常。
     *
     * @param message 异常消息
     * @param cause   根因
     */
    public MappingLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}