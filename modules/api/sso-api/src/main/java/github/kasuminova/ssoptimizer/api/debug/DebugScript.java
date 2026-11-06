package github.kasuminova.ssoptimizer.api.debug;

import java.util.Map;

/**
 * 游戏内动态编译脚本的执行契约。
 * <p>
 * 实现必须提供公开无参构造。默认在独立线程执行；选择 {@code main} 模式时与游戏主循环串行执行。
 * 每次调用同步等待脚本完成，脚本不得假定其他线程模型。
 */
public interface DebugScript {
    /**
     * 使用给定上下文和参数执行脚本逻辑，脚本产生的副作用由实现自行约束。
     *
     * @param ctx  调试上下文，提供类加载、日志和跨域服务访问
     * @param args JSON 对象反序列化得到的参数，数值为 {@code Double}；无参数时为空映射
     * @return 可由 JSON 序列化的结果对象；序列化失败由调试引擎作为调用错误返回
     * @throws Exception 脚本执行失败时抛出，由调试引擎转换为 RPC 内部错误
     */
    Object invoke(DebugContext ctx, Map<String, Object> args) throws Exception;
}
