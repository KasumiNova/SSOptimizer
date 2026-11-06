package github.kasuminova.ssoptimizer.common.debug;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;

/** 仅接受单对象请求，非对象 params 按 null 处理。 */
public final class DebugRpcRouter {
    public static final int CODE_PARSE_ERROR = -32700;

    public static final int CODE_INVALID_REQUEST = -32600;

    public static final int CODE_METHOD_NOT_FOUND = -32601;

    public static final int CODE_INTERNAL_ERROR = -32603;

    @FunctionalInterface
    public interface RpcHandler {
        JsonElement invoke(JsonObject params) throws Exception;
    }

    private final Map<String, RpcHandler> handlers = new LinkedHashMap<>();

    public static DebugRpcRouter createDefault() {
        final DebugRpcRouter router = new DebugRpcRouter();
        router.register("ping", params -> {
            final JsonObject pong = new JsonObject();
            pong.addProperty("pong", true);
            pong.addProperty("time", System.currentTimeMillis());
            return pong;
        });
        router.register("version", params -> {
            final JsonObject version = new JsonObject();
            version.addProperty("name", "SSOptimizer");
            final String implVersion = DebugRpcRouter.class.getPackage().getImplementationVersion();
            version.addProperty("version", implVersion == null ? "dev" : implVersion);
            version.addProperty("protocol", 1);
            return version;
        });
        return router;
    }

    public void register(final String method, final RpcHandler handler) {
        if (handlers.putIfAbsent(method, handler) != null) {
            throw new IllegalArgumentException("[SSOptimizer] Duplicate debug RPC method: " + method);
        }
    }

    public String dispatchRaw(final String rawBody) {
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(rawBody);
        } catch (final JsonParseException e) {
            return errorResponse(null, CODE_PARSE_ERROR, "Parse error: " + e.getMessage()).toString();
        }
        if (!parsed.isJsonObject()) {
            return errorResponse(null, CODE_INVALID_REQUEST, "Request must be a JSON object").toString();
        }
        return dispatch(parsed.getAsJsonObject()).toString();
    }

    public JsonObject dispatch(final JsonObject request) {
        final JsonElement id = request.get("id");
        if (!request.has("method") || !request.get("method").isJsonPrimitive()) {
            return errorResponse(id, CODE_INVALID_REQUEST, "Missing or invalid 'method'");
        }
        final String method = request.get("method").getAsString();
        final RpcHandler handler = handlers.get(method);
        if (handler == null) {
            return errorResponse(id, CODE_METHOD_NOT_FOUND, "Method not found: " + method);
        }
        final JsonElement paramsElement = request.get("params");
        final JsonObject params = paramsElement != null && paramsElement.isJsonObject()
                ? paramsElement.getAsJsonObject() : null;
        try {
            final JsonElement result = handler.invoke(params);
            final JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.add("result", result);
            response.add("id", id);
            return response;
        } catch (final Exception e) {
            return errorResponse(id, CODE_INTERNAL_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static JsonObject errorResponse(final JsonElement id, final int code, final String message) {
        final JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        final JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("error", error);
        response.add("id", id);
        return response;
    }
}
