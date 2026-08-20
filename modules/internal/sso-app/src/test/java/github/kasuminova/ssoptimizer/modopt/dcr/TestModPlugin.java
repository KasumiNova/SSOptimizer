package github.kasuminova.ssoptimizer.modopt.dcr;

/**
 * 测试接口：让转换后（子加载器加载）的 plugin 夹具可经父加载器接口被调用，避免对其方法用反射。
 */
public interface TestModPlugin {

    /** 对应夹具的 {@code onGameLoad(boolean)}（被处理器 redirect/inject 的目标方法）。 */
    void onGameLoad(boolean newGame);

    /** 返回当前缓存战报数，用于断言合并后末态与逐条一致。 */
    int cacheSize();
}
