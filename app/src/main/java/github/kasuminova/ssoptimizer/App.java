package github.kasuminova.ssoptimizer;

import org.apache.log4j.Logger;

/**
 * SSOptimizer 主入口类。
 *
 * <p>当 SSOptimizer JAR 作为独立应用运行时（而非作为 javaagent），
 * 此类的 {@code main} 方法被调用，仅输出引导信息。</p>
 */
public final class App {
    private static final Logger LOGGER = Logger.getLogger(App.class);

    private App() {
    }

    static void main(String[] args) {
        LOGGER.info("[SSOptimizer] SSOptimizer bootstrap running on Java " + Runtime.version());
    }
}
