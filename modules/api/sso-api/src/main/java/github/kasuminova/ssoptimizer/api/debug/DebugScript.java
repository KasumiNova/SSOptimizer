package github.kasuminova.ssoptimizer.api.debug;

import java.util.Map;

/**
 * 调试脚本契约：游戏内动态编译执行的代码单元。
 *
 * <p>动机：Agent 调试需要「改一段逻辑 → 立刻在游戏内执行 → 读回状态」的秒级反馈环。
 * 脚本源码经 {@code script_compile} RPC 编译为独立类加载器中的类并实例化；
 * 同名 scriptId 再编译即替换实例（L1 热重载），脚本内可经
 * {@link DebugContext#gameClassLoader()} 加载游戏/模组类（运行时为 named 命名）。</p>
 *
 * <p>实现约束：公开无参构造；执行线程由调用方 threadMode 决定
 * （默认新线程；{@code main} 模式与游戏主循环串行）。</p>
 */
public interface DebugScript {
    /**
     * 执行脚本逻辑。
     *
     * @param ctx  调试上下文（类加载器、日志、跨域服务解析）
     * @param args 调用参数（JSON 反序列化的键值对，数值为 Double）
     * @return 结果对象，须为 JSON 可序列化（基本类型/字符串/Map/List），
     *         否则调用方以序列化错误返回
     * @throws Exception 脚本内任何异常由引擎收敛为 RPC 内部错误返回
     */
    Object invoke(DebugContext ctx, Map<String, Object> args) throws Exception;
}
