package github.kasuminova.ssoptimizer.common.debug;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DebugServerImpl} 集成测试：真实 HttpServer 绑定临时端口，
 * 验证鉴权、HTTP 方法约束、RPC 往返与 token 文件。
 */
class DebugServerImplTest {
    @TempDir
    Path outputDir;

    private DebugServerImpl server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void pingRoundTripWithToken() throws Exception {
        startServer("test-token");
        final HttpResponse<String> response = post("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}", "test-token");
        assertEquals(200, response.statusCode());
        assertTrue(JsonParser.parseString(response.body()).getAsJsonObject()
                .getAsJsonObject("result").get("pong").getAsBoolean());
    }

    @Test
    void missingTokenYields401() throws Exception {
        startServer("test-token");
        final HttpResponse<String> response = post("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}", null);
        assertEquals(401, response.statusCode());
    }

    @Test
    void wrongTokenYields401() throws Exception {
        startServer("test-token");
        final HttpResponse<String> response = post("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}", "wrong");
        assertEquals(401, response.statusCode());
    }

    @Test
    void getYields405() throws Exception {
        startServer("test-token");
        final HttpRequest request = HttpRequest.newBuilder(rpcUri())
                .header("Authorization", "Bearer test-token")
                .GET().build();
        assertEquals(405, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void generatedTokenWrittenToFile() throws Exception {
        startServer("");
        assertFalse(server.token().isEmpty());
        final Path tokenPath = outputDir.resolve(DebugConfig.TOKEN_FILE);
        assertTrue(Files.exists(tokenPath));
        assertEquals(server.token(), Files.readString(tokenPath));
    }

    @Test
    void doubleStartRejected() {
        startServer("test-token");
        assertThrows(IllegalStateException.class, () -> server.start());
    }

    @Test
    void stopClearsRunningState() {
        startServer("test-token");
        assertTrue(server.isRunning());
        final int port = server.boundPort();
        assertTrue(port > 0);
        server.stop();
        assertFalse(server.isRunning());
        assertEquals(-1, server.boundPort());
    }

    private void startServer(final String token) {
        server = new DebugServerImpl(new DebugConfig(true, 0, outputDir, token), DebugRpcRouter.createDefault());
        server.start();
    }

    private URI rpcUri() {
        return URI.create("http://127.0.0.1:" + server.boundPort() + "/rpc");
    }

    private HttpResponse<String> post(final String body, final String token) throws IOException, InterruptedException {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(rpcUri())
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
