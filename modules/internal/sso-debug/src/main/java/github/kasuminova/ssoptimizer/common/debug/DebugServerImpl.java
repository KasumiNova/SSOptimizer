package github.kasuminova.ssoptimizer.common.debug;

import com.sun.net.httpserver.HttpServer;
import github.kasuminova.ssoptimizer.common.concurrent.VtWorkers;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;

/**
 * {@link DebugServer} 的 jdk.httpserver 实现。
 *
 * <p>设计要点：只绑定回环地址（调试接口是「受信任的破坏力」，不暴露到外部网络）；
 * 全部请求要求 {@code Authorization: Bearer <token>}；HTTP 处理器跑在
 * {@link VtWorkers} 虚拟线程上，阻塞 IO 不占游戏主线程。</p>
 */
public final class DebugServerImpl implements DebugServer {
    private static final Logger LOGGER = Logger.getLogger(DebugServerImpl.class);

    private final DebugConfig config;
    private final DebugRpcRouter router;

    private volatile HttpServer server;
    private volatile String token;

    /**
     * 创建调试服务实例（未启动）。
     *
     * @param config 调试配置
     * @param router RPC 路由器
     */
    public DebugServerImpl(final DebugConfig config, final DebugRpcRouter router) {
        this.config = config;
        this.router = router;
    }

    @Override
    public synchronized void start() {
        if (server != null) {
            throw new IllegalStateException("[SSOptimizer] DebugServer already started");
        }
        token = config.token().isEmpty() ? generateToken() : config.token();

        IOException lastFailure = null;
        for (int offset = 0; offset < DebugConfig.PORT_RETRY_SPAN; offset++) {
            // port==0 为临时端口语义，递增重试无意义，单次尝试
            if (config.port() == 0 && offset > 0) {
                break;
            }
            final int candidate = config.port() == 0 ? 0 : config.port() + offset;
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", candidate), 0);
                break;
            } catch (final IOException e) {
                lastFailure = e;
                LOGGER.debug("[SSOptimizer] Debug port " + candidate + " unavailable: " + e.getMessage());
            }
        }
        if (server == null) {
            throw new IllegalStateException("[SSOptimizer] DebugServer: no available port in span "
                    + config.port() + "–" + (config.port() + DebugConfig.PORT_RETRY_SPAN - 1), lastFailure);
        }

        server.setExecutor(VtWorkers::submit);
        server.createContext("/rpc", exchange -> {
            try (exchange) {
                handleRpc(exchange);
            }
        });
        try {
            server.start();
            writeTokenFile();
        } catch (final RuntimeException e) {
            // 启动失败回滚：避免「服务在监听但调用方拿不到引用」的半启动态
            server.stop(0);
            server = null;
            token = null;
            throw e;
        }
        LOGGER.info("[SSOptimizer] DebugServer listening on 127.0.0.1:" + boundPort()
                + " (token file: " + config.tokenPath() + ")");
    }

    @Override
    public synchronized void stop() {
        if (server == null) {
            return;
        }
        server.stop(0);
        server = null;
        token = null;
        LOGGER.info("[SSOptimizer] DebugServer stopped");
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }

    @Override
    public int boundPort() {
        final HttpServer current = server;
        return current == null ? -1 : current.getAddress().getPort();
    }

    @Override
    public String token() {
        return token;
    }

    private void handleRpc(final com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        final String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (!("Bearer " + token).equals(authorization)) {
            exchange.sendResponseHeaders(401, -1);
            return;
        }
        final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        final byte[] response = router.dispatchRaw(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
    }

    private void writeTokenFile() {
        try {
            final Path tokenPath = config.tokenPath();
            Files.createDirectories(tokenPath.getParent());
            Files.writeString(tokenPath, token, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tokenPath, PosixFilePermissions.fromString("rw-------"));
            } catch (final UnsupportedOperationException e) {
                // 非 POSIX 文件系统（如 Windows NTFS）：无权限位概念，降级为普通文件
                LOGGER.debug("[SSOptimizer] Debug token file: POSIX permissions unsupported, skipped");
            }
        } catch (final IOException e) {
            throw new IllegalStateException("[SSOptimizer] DebugServer: failed to write token file "
                    + config.tokenPath(), e);
        }
    }

    private static String generateToken() {
        final byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
