package github.kasuminova.ssoptimizer.common.debug;

public interface DebugServer {
    void start();

    void stop();

    boolean isRunning();

    int boundPort();

    String token();
}
