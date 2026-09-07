package github.kasuminova.ssoptimizer.api.debug;

/**
 * 调试脚本执行上下文。
 *
 * <p>动机：脚本类由独立类加载器加载，与游戏/模组的交互入口统一收敛在本接口，
 * 避免脚本各自寻找入口句柄。实现由 sso-debug 模块提供（{@code DebugContextImpl}）。</p>
 */
public interface DebugContext {
    /**
     * 返回游戏运行时类加载器（生产环境为 LaunchClassLoader）。
     * 脚本经 {@code Class.forName(name, true, gameClassLoader())} 加载游戏/模组类。
     *
     * @return 游戏类加载器
     */
    ClassLoader gameClassLoader();

    /**
     * 输出 INFO 级日志到游戏日志（logger 名 {@code SSOptimizer.DebugScript}）。
     *
     * @param message 日志内容
     */
    void log(String message);

    /**
     * 解析跨域服务（ServiceRegistry 语义）。
     *
     * @param api 服务接口类型
     * @param <T> 服务类型
     * @return 服务实例；未注册时返回 null（调用点须判空）
     */
    <T> T service(Class<T> api);
}
