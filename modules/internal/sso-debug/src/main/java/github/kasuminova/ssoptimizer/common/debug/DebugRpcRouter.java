package github.kasuminova.ssoptimizer.common.debug;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON-RPC 2.0 请求路由器。
 *
 * <p>动机：调试服务的 HTTP 层只负责传输与鉴权，方法分发与协议错误码收敛在本类，
 * 使协议逻辑可脱离 HTTP 直接单测。单对象请求模型，不支持 batch 数组；
 * 非对象 {@code params}（数组/基本类型）按缺省 null 处理（调试协议的宽容语义，
 * 不做严格 -32602 拒绝）。</p>
 */
public final class DebugRpcRouter {
    /** JSON-RPC 解析失败错误码。 */
    public static final int CODE_PARSE_ERROR = -32700;

    /** JSON-RPC 无效请求错误码（缺 method 等协议字段）。 */
    public static final int CODE_INVALID_REQUEST = -32600;

    /** JSON-RPC 方法不存在错误码。 */
    public static final int CODE_METHOD_NOT_FOUND = -32601;

    /** JSON-RPC 内部错误码（处理器抛出的异常透传为 message）。 */
    public static final int CODE_INTERNAL_ERROR = -32603;

    /**
     * RPC 方法处理器契约。
     *
     * <p>入参为请求的 {@code params} 对象（缺省时为 null），返回值的
     * {@link JsonElement} 直接作为响应的 {@code result} 字段。</p>
     */
    @FunctionalInterface
    public interface RpcHandler {
        /**
         * 执行 RPC 方法。
         *
         * @param params 请求参数对象，可能为 null
         * @return 结果 JSON
         * @throws Exception 处理器内任何异常均被路由层收敛为 {@link #CODE_INTERNAL_ERROR}
         */
        JsonElement invoke(JsonObject params) throws Exception;
    }

    private final Map<String, RpcHandler> handlers = new LinkedHashMap<>();

    /**
     * 创建注册有内建方法（{@code ping} / {@code version}）的路由器。
     *
     * @return 默认路由器
     */
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

    /**
     * 注册 RPC 方法处理器。同名重复注册抛出 {@link IllegalArgumentException}。
     *
     * @param method  方法名
     * @param handler 处理器
     */
    public void register(final String method, final RpcHandler handler) {
        if (handlers.putIfAbsent(method, handler) != null) {
            throw new IllegalArgumentException("[SSOptimizer] Duplicate debug RPC method: " + method);
        }
    }

    /**
     * 分发原始请求体：解析 JSON 后进入 {@link #dispatch(JsonObject)}，
     * 解析失败收敛为 {@link #CODE_PARSE_ERROR} 响应。
     *
     * @param rawBody 请求体文本
     * @return 响应 JSON 文本
     */
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

    /**
     * 分发已解析的请求对象。
     *
     * @param request JSON-RPC 请求对象
     * @return 响应 JSON 对象
     */
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
