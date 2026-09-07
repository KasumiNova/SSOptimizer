package github.kasuminova.ssoptimizer.common.debug;

/**
 * 游戏内调试 HTTP RPC 服务契约。
 *
 * <p>动机：Agent 调试场景需要秒级反馈通道（脚本执行、热重载、状态查询），
 * 调试服务以 localhost HTTP 端点承载这些能力，默认关闭、token 鉴权。
 * 实现见 {@link DebugServerImpl}，装配入口见 {@link DebugServerBootstrap}。</p>
 */
public interface DebugServer {
    /**
     * 启动服务：解析 token、绑定端口（冲突时在 {@link DebugConfig#PORT_RETRY_SPAN}
     * 跨度内递增重试）、写出 token 文件。重复调用抛出 {@link IllegalStateException}。
     */
    void start();

    /**
     * 停止服务并释放端口。未启动时调用为空操作。
     */
    void stop();

    /**
     * 返回服务是否处于运行状态。
     *
     * @return true 表示已启动且未停止
     */
    boolean isRunning();

    /**
     * 返回实际绑定的端口（端口冲突重试或传入 0 临时端口后的真实值）。
     *
     * @return 绑定端口；未启动时返回 -1
     */
    int boundPort();

    /**
     * 返回生效的鉴权 token（显式配置或启动时随机生成值）。
     *
     * @return 鉴权 token；未启动时返回 null
     */
    String token();
}
