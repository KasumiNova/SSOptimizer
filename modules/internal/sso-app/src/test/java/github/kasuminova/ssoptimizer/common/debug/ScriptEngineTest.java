package github.kasuminova.ssoptimizer.common.debug;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 脚本引擎测试：编译、调用、L1 热重载、threadMode 分发与 RPC 接线。
 */
class ScriptEngineTest {
    private static final String ECHO_SOURCE = """
            public class EchoScript implements github.kasuminova.ssoptimizer.api.debug.DebugScript {
                @Override
                public Object invoke(github.kasuminova.ssoptimizer.api.debug.DebugContext ctx,
                                     java.util.Map<String, Object> args) {
                    return "echo:" + args.get("value");
                }
            }
            """;

    private static final String THREAD_PROBE_SOURCE = """
            public class ThreadProbeScript implements github.kasuminova.ssoptimizer.api.debug.DebugScript {
                @Override
                public Object invoke(github.kasuminova.ssoptimizer.api.debug.DebugContext ctx,
                                     java.util.Map<String, Object> args) {
                    return Thread.currentThread();
                }
            }
            """;

    private final ScriptRegistry registry = new ScriptRegistry(
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "sso-debug-test"));

    @Test
    void compileAndInvokeNewThread() throws Exception {
        registry.compile("echo", "EchoScript", ECHO_SOURCE);
        assertEquals("echo:42", registry.invoke("echo", Map.of("value", 42), ThreadMode.NEW));
    }

    @Test
    void recompileReplacesBehavior() throws Exception {
        registry.compile("echo", "EchoScript", ECHO_SOURCE);
        final ScriptHandle v2 = registry.compile("echo", "EchoScript", ECHO_SOURCE.replace("echo:", "v2:"));
        assertEquals(2, v2.version());
        assertEquals("v2:1", registry.invoke("echo", Map.of("value", 1), ThreadMode.NEW));
        // 重载后旧句柄实例不再被注册表持有：list 只有新句柄
        assertEquals(1, registry.list().size());
        assertSame(v2.instance(), registry.list().get(0).instance());
    }

    @Test
    void compileFailureCarriesDiagnostics() {
        final ScriptCompiler.ScriptCompileException failure = assertThrows(
                ScriptCompiler.ScriptCompileException.class,
                () -> registry.compile("bad", "BadScript", "public class BadScript { this is not java }"));
        assertTrue(failure.getMessage().contains("line"));
    }

    @Test
    void mismatchedPublicClassNameFailsCompile() {
        // 公开类名必须与源文件名（取自 className）一致，否则 javac 直接拒绝
        assertThrows(ScriptCompiler.ScriptCompileException.class,
                () -> registry.compile("echo", "NoSuchScript", ECHO_SOURCE));
    }

    @Test
    void nonDebugScriptClassRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.compile("plain", "PlainClass", "public class PlainClass { }"));
    }

    @Test
    void unknownScriptIdRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> registry.invoke("nope", Map.of(), ThreadMode.NEW));
    }

    @Test
    void mainThreadModeRunsOnDrainingThread() throws Exception {
        registry.compile("threadProbe", "ThreadProbeScript", THREAD_PROBE_SOURCE);
        final Thread drainingThread = Thread.currentThread();
        final AtomicReference<Object> result = new AtomicReference<>();
        final Thread caller = new Thread(() -> {
            try {
                result.set(registry.invoke("threadProbe", Map.of(), ThreadMode.MAIN));
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        });
        caller.start();
        // 等待任务入队后在当前线程（模拟游戏主线程）排空
        while (result.get() == null && caller.isAlive()) {
            MainThreadTasks.drain();
            Thread.sleep(1);
        }
        caller.join(5000);
        assertSame(drainingThread, result.get());
    }

    @Test
    void newThreadModeDoesNotRunOnCaller() throws Exception {
        registry.compile("threadProbe", "ThreadProbeScript", THREAD_PROBE_SOURCE);
        final Object thread = registry.invoke("threadProbe", Map.of(), ThreadMode.NEW);
        assertNotSame(Thread.currentThread(), thread);
    }

    @Test
    void mainThreadModeTimesOutWithoutDrain() {
        registry.compile("threadProbe", "ThreadProbeScript", THREAD_PROBE_SOURCE);
        final IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> registry.invoke("threadProbe", Map.of(), ThreadMode.MAIN));
        assertTrue(failure.getMessage().contains("did not drain"));
    }

    @Test
    void threadModeParsing() {
        assertEquals(ThreadMode.NEW, ThreadMode.parse(null));
        assertEquals(ThreadMode.NEW, ThreadMode.parse("  "));
        assertEquals(ThreadMode.NEW, ThreadMode.parse("new"));
        assertEquals(ThreadMode.MAIN, ThreadMode.parse("MAIN"));
        assertThrows(IllegalArgumentException.class, () -> ThreadMode.parse("bogus"));
    }

    @Test
    void rpcRoundTrip() {
        final DebugRpcRouter router = DebugRpcRouter.createDefault();
        registry.registerRpc(router);
        final JsonObject compileResponse = dispatch(router, """
                {"jsonrpc":"2.0","method":"script_compile","id":1,"params":{
                  "scriptId":"echo","className":"EchoScript","source":%s}}
                """.formatted(jsonString(ECHO_SOURCE)));
        assertEquals(1, compileResponse.getAsJsonObject("result").get("version").getAsInt());

        final JsonObject invokeResponse = dispatch(router, """
                {"jsonrpc":"2.0","method":"script_invoke","id":2,"params":{
                  "scriptId":"echo","args":{"value":7}}}
                """);
        assertEquals("echo:7.0", invokeResponse.get("result").getAsString());

        final JsonObject listResponse = dispatch(router,
                "{\"jsonrpc\":\"2.0\",\"method\":\"script_list\",\"id\":3}");
        assertEquals(1, listResponse.getAsJsonArray("result").size());
        assertEquals("echo", listResponse.getAsJsonArray("result").get(0)
                .getAsJsonObject().get("scriptId").getAsString());
    }

    @Test
    void rpcCompileErrorSurfaced() {
        final DebugRpcRouter router = DebugRpcRouter.createDefault();
        registry.registerRpc(router);
        final JsonObject response = dispatch(router, """
                {"jsonrpc":"2.0","method":"script_compile","id":1,"params":{
                  "scriptId":"bad","className":"Bad","source":"not java at all"}}
                """);
        assertEquals(DebugRpcRouter.CODE_INTERNAL_ERROR,
                response.getAsJsonObject("error").get("code").getAsInt());
    }

    private static JsonObject dispatch(final DebugRpcRouter router, final String raw) {
        return JsonParser.parseString(router.dispatchRaw(raw)).getAsJsonObject();
    }

    private static String jsonString(final String text) {
        return new com.google.gson.Gson().toJson(text);
    }
}
