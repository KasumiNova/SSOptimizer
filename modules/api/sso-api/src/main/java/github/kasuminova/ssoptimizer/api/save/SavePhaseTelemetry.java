package github.kasuminova.ssoptimizer.api.save;

/**
 * 存档阶段遥测：对外暴露读档/存档各阶段的最近一次耗时。
 * <p>
 * 动机：automation 域的存档读写自动化驱动需要读取 save 域的 unmarshal 阶段耗时
 * 输出遥测，但功能模块间禁止直接依赖——跨域行为调用经本接口。
 * <p>
 * 实现由 save 域提供（桥接 UnmarshalPhaseTimer），在 coremod 装配期经
 * {@code ServiceRegistry} 注册；仅自动化场景消费，游戏正常流程不触达。
 */
public interface SavePhaseTelemetry {

    /**
     * @return 最近一次存档反序列化（unmarshal）阶段耗时（毫秒）；尚未发生过读档时返回 0
     */
    long lastUnmarshalMs();
}
