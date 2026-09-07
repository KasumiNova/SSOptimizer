package github.kasuminova.ssoptimizer.common.debug;

import java.nio.file.Path;

/**
 * SSOptimizer 游戏内调试服务配置。
 *
 * <p>该类只读取系统属性并形成不可变配置，供 coremod 装配入口与测试共享。
 * 调试服务默认关闭：它是面向本地 Agent 调试的受信任接口，生产运行不启动。</p>
 *
 * @param enabled   是否启用调试服务
 * @param port      HTTP 监听端口起始值（冲突时递增重试，见 {@link #PORT_RETRY_SPAN}）
 * @param outputDir 调试输出目录（token 文件、脚本编译临时产物）
 * @param token     显式鉴权 token；空白表示启动时随机生成
 */
public record DebugConfig(boolean enabled,
                          int port,
                          Path outputDir,
                          String token) {
    /** 调试服务总开关系统属性。 */
    public static final String ENABLED_PROPERTY = "ssoptimizer.debug.enabled";

    /** 调试服务端口系统属性。 */
    public static final String PORT_PROPERTY = "ssoptimizer.debug.port";

    /** 调试输出目录系统属性。 */
    public static final String OUTPUT_DIR_PROPERTY = "ssoptimizer.debug.outputDir";

    /** 显式鉴权 token 系统属性（空白 = 启动时随机生成并写入 token 文件）。 */
    public static final String TOKEN_PROPERTY = "ssoptimizer.debug.token";

    /** 默认监听端口。 */
    public static final int DEFAULT_PORT = 8471;

    /** 端口冲突时的递增重试跨度（8471–8480）。 */
    public static final int PORT_RETRY_SPAN = 10;

    /** token 文件名（写入 {@code outputDir} 下，供本地调试客户端读取）。 */
    public static final String TOKEN_FILE = "debug-token";

    /**
     * 从当前 JVM 系统属性读取调试服务配置。
     *
     * @return 调试服务配置
     */
    public static DebugConfig fromSystemProperties() {
        final boolean enabled = Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
        final int port = Integer.parseInt(System.getProperty(PORT_PROPERTY, String.valueOf(DEFAULT_PORT)));
        final String explicitOutputDir = System.getProperty(OUTPUT_DIR_PROPERTY, "").trim();
        final Path outputDir = explicitOutputDir.isEmpty()
                ? Path.of(System.getProperty("user.dir", "."), "ssoptimizer-debug-output")
                : Path.of(explicitOutputDir);
        final String token = System.getProperty(TOKEN_PROPERTY, "").trim();
        return new DebugConfig(enabled, port, outputDir, token);
    }

    /**
     * 返回鉴权 token 文件的绝对路径。
     *
     * @return token 文件路径
     */
    public Path tokenPath() {
        return outputDir.resolve(TOKEN_FILE);
    }
}
