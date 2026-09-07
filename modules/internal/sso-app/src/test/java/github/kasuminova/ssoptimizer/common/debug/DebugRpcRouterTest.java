package github.kasuminova.ssoptimizer.common.debug;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DebugRpcRouter} 协议分发测试：内建方法、错误码收敛。
 */
class DebugRpcRouterTest {
    private final DebugRpcRouter router = DebugRpcRouter.createDefault();

    @Test
    void pingReturnsPong() {
        final JsonObject response = dispatch("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":1}");
        assertTrue(response.getAsJsonObject("result").get("pong").getAsBoolean());
        assertEquals(1, response.get("id").getAsInt());
    }

    @Test
    void versionReturnsIdentity() {
        final JsonObject response = dispatch("{\"jsonrpc\":\"2.0\",\"method\":\"version\",\"id\":2}");
        final JsonObject result = response.getAsJsonObject("result");
        assertEquals("SSOptimizer", result.get("name").getAsString());
        assertEquals(1, result.get("protocol").getAsInt());
        assertFalse(result.get("version").getAsString().isEmpty());
    }

    @Test
    void unknownMethodYieldsMethodNotFound() {
        final JsonObject response = dispatch("{\"jsonrpc\":\"2.0\",\"method\":\"nope\",\"id\":3}");
        assertEquals(DebugRpcRouter.CODE_METHOD_NOT_FOUND, response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void malformedJsonYieldsParseError() {
        final JsonObject response = dispatch("{not json");
        assertEquals(DebugRpcRouter.CODE_PARSE_ERROR, response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void nonObjectRequestYieldsInvalidRequest() {
        final JsonObject response = dispatch("[1,2,3]");
        assertEquals(DebugRpcRouter.CODE_INVALID_REQUEST, response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void missingMethodYieldsInvalidRequest() {
        final JsonObject response = dispatch("{\"jsonrpc\":\"2.0\",\"id\":4}");
        assertEquals(DebugRpcRouter.CODE_INVALID_REQUEST, response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void handlerExceptionYieldsInternalError() {
        router.register("boom", params -> { throw new IllegalStateException("expected failure"); });
        final JsonObject response = dispatch("{\"jsonrpc\":\"2.0\",\"method\":\"boom\",\"id\":5}");
        final JsonObject error = response.getAsJsonObject("error");
        assertEquals(DebugRpcRouter.CODE_INTERNAL_ERROR, error.get("code").getAsInt());
        assertTrue(error.get("message").getAsString().contains("expected failure"));
    }

    @Test
    void duplicateRegistrationRejected() {
        assertThrows(IllegalArgumentException.class, () -> router.register("ping", params -> null));
    }

    @Test
    void paramsArePassedToHandler() {
        router.register("echo", params -> params.get("value"));
        final JsonObject response = dispatch(
                "{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"params\":{\"value\":42},\"id\":6}");
        assertEquals(42, response.get("result").getAsInt());
    }

    private JsonObject dispatch(final String raw) {
        return JsonParser.parseString(router.dispatchRaw(raw)).getAsJsonObject();
    }
}
