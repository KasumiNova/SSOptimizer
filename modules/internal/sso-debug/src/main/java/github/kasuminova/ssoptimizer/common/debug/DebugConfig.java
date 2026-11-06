package github.kasuminova.ssoptimizer.common.debug;

import java.nio.file.Path;

public record DebugConfig(boolean enabled,
                          int port,
                          Path outputDir,
                          String token) {
    public static final String ENABLED_PROPERTY = "ssoptimizer.debug.enabled";

    public static final String PORT_PROPERTY = "ssoptimizer.debug.port";

    public static final String OUTPUT_DIR_PROPERTY = "ssoptimizer.debug.outputDir";

    public static final String TOKEN_PROPERTY = "ssoptimizer.debug.token";

    public static final int DEFAULT_PORT = 8471;

    public static final int PORT_RETRY_SPAN = 10;

    public static final String TOKEN_FILE = "debug-token";

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

    public Path tokenPath() {
        return outputDir.resolve(TOKEN_FILE);
    }
}
