package github.kasuminova.ssoptimizer.api.debug;

/**
 * 为动态脚本提供游戏类加载、日志和跨域服务访问入口，隔离脚本与调试模块实现。
 * <p>
 * 所有方法均在脚本当前执行线程同步运行，不切换线程；实现须支持不同脚本并发调用。
 */
public interface DebugContext {
    /**
     * 返回可加载游戏与模组类的运行时类加载器。
     *
     * @return 游戏运行时类加载器，不会为脚本创建新的类加载器
     */
    ClassLoader gameClassLoader();

    /**
     * 向游戏日志写入一条 INFO 记录，logger 名为 {@code SSOptimizer.DebugScript}。
     *
     * @param message 日志内容
     */
    void log(String message);

    /**
     * 从跨域服务注册表解析服务，不执行服务初始化或线程切换。
     *
     * @param api 服务接口类型
     * @param <T> 服务类型
     * @return 已注册的服务实例；未注册 = 服务不可用，返回 {@code null}，调用方必须判空
     */
    <T> T service(Class<T> api);
}
