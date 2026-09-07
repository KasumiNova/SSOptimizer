package github.kasuminova.ssoptimizer.common.debug;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import github.kasuminova.ssoptimizer.api.debug.DebugScript;
import github.kasuminova.ssoptimizer.common.concurrent.VtWorkers;
import org.apache.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * 调试脚本引擎：编译注册、句柄管理、L1 热重载与 RPC 接线。
 *
 * <p>动机：脚本全生命周期收敛在一处——同名 scriptId 重编译即替换句柄
 * （新类加载器 + 新实例，旧产物随引用解除被 GC）；执行按 threadMode
 * 分流到新线程或游戏主线程。RPC 方法：{@code script_compile} /
 * {@code script_invoke} / {@code script_list}。</p>
 */
public final class ScriptRegistry {
    private static final Logger LOGGER = Logger.getLogger(ScriptRegistry.class);
    private static final Gson GSON = new Gson();

    private final ScriptCompiler compiler = new ScriptCompiler();
    private final Map<String, ScriptHandle> handles = new ConcurrentHashMap<>();
    private final java.nio.file.Path outputDir;

    /**
     * 创建脚本引擎。
     *
     * @param outputDir 调试输出目录（hotswap agent jar 物化位置）
     */
    public ScriptRegistry(final java.nio.file.Path outputDir) {
        this.outputDir = outputDir;
    }

    /**
     * 编译并注册脚本。同名 scriptId 替换旧句柄（L1 热重载）。
     *
     * @param scriptId  脚本句柄 ID
     * @param className 脚本主类全限定名（须实现 {@link DebugScript} 且有公开无参构造）
     * @param source    Java 源码全文
     * @return 新句柄
     */
    public synchronized ScriptHandle compile(final String scriptId, final String className, final String source) {
        final Map<String, byte[]> classes = compiler.compile(className, source);
        final ScriptClassLoader loader = new ScriptClassLoader(classes, DebugContextImpl.class.getClassLoader());
        final DebugScript instance = instantiate(loader, className);
        final ScriptHandle previous = handles.get(scriptId);
        final ScriptHandle handle = new ScriptHandle(scriptId, className,
                previous == null ? 1 : previous.version() + 1,
                instance, loader, System.currentTimeMillis());
        handles.put(scriptId, handle);
        LOGGER.info("[SSOptimizer] Debug script compiled: " + scriptId + " v" + handle.version()
                + " (" + className + ")");
        return handle;
    }

    /**
     * 执行已注册脚本。
     *
     * @param scriptId 脚本句柄 ID
     * @param args     调用参数
     * @param mode     执行线程模式
     * @return 脚本返回值
     * @throws Exception 脚本或调度异常
     */
    public Object invoke(final String scriptId, final Map<String, Object> args, final ThreadMode mode)
            throws Exception {
        final ScriptHandle handle = handles.get(scriptId);
        if (handle == null) {
            throw new IllegalArgumentException("[SSOptimizer] Unknown debug script: " + scriptId);
        }
        final Map<String, Object> effectiveArgs = args == null ? Map.of() : args;
        final Callable<Object> task = () -> handle.instance().invoke(DebugContextImpl.INSTANCE, effectiveArgs);
        return switch (mode) {
            case NEW -> submitNewThread(task);
            case MAIN -> MainThreadTasks.call(task);
        };
    }

    /**
     * 返回全部已注册脚本句柄（注册序）。
     *
     * @return 句柄列表
     */
    public List<ScriptHandle> list() {
        return List.copyOf(handles.values());
    }

    /**
     * 把脚本 RPC 方法注册进路由器。
     *
     * @param router 调试 RPC 路由器
     */
    public void registerRpc(final DebugRpcRouter router) {
        router.register("script_compile", params -> {
            final ScriptHandle handle = compile(
                    requireParam(params, "scriptId"), requireParam(params, "className"),
                    requireParam(params, "source"));
            final JsonObject result = new JsonObject();
            result.addProperty("scriptId", handle.scriptId());
            result.addProperty("className", handle.className());
            result.addProperty("version", handle.version());
            result.addProperty("compiledAt", handle.compiledAt());
            return result;
        });
        router.register("script_invoke", params -> {
            final String scriptId = requireParam(params, "scriptId");
            final Map<String, Object> args = params.has("args") && params.get("args").isJsonObject()
                    ? GSON.fromJson(params.getAsJsonObject("args"),
                            new com.google.gson.reflect.TypeToken<Map<String, Object>>() { }.getType())
                    : Map.of();
            final ThreadMode mode = params.has("threadMode")
                    ? ThreadMode.parse(params.get("threadMode").getAsString())
                    : ThreadMode.NEW;
            final Object value = invoke(scriptId, args, mode);
            return GSON.toJsonTree(value);
        });
        router.register("script_list", params -> {
            final JsonArray result = new JsonArray();
            for (final ScriptHandle handle : list()) {
                final JsonObject entry = new JsonObject();
                entry.addProperty("scriptId", handle.scriptId());
                entry.addProperty("className", handle.className());
                entry.addProperty("version", handle.version());
                entry.addProperty("compiledAt", handle.compiledAt());
                result.add(entry);
            }
            return result;
        });
        // 主线程排空计数探针：判断游戏主循环是否在跑（发 main 模式任务前先确认）
        router.register("debug_stats", params -> {
            final JsonObject result = new JsonObject();
            result.addProperty("drainCount", MainThreadTasks.drainCount());
            result.addProperty("scripts", handles.size());
            return result;
        });
        // L2 方法体 hotswap：编译源码 → redefineClasses 已加载类（仅方法体可替换）
        router.register("hotswap", params -> {
            final String className = requireParam(params, "className");
            final Map<String, byte[]> classes = compiler.compile(className, requireParam(params, "source"));
            final byte[] bytes = classes.get(className);
            if (bytes == null) {
                throw new IllegalArgumentException(
                        "[SSOptimizer] hotswap compilation output has no class: " + className);
            }
            if (classes.size() > 1) {
                LOGGER.warn("[SSOptimizer] hotswap " + className + ": compilation produced "
                        + (classes.size() - 1) + " auxiliary class(es) — only the main class is redefined, "
                        + "references to new nested classes will fail at runtime");
            }
            HotswapSupport.redefine(className, bytes, outputDir);
            final JsonObject result = new JsonObject();
            result.addProperty("className", className);
            result.addProperty("redefined", true);
            return result;
        });
    }

    private static Object submitNewThread(final Callable<Object> task) throws Exception {
        try {
            return VtWorkers.submit(task).get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("[SSOptimizer] script invoke interrupted", e);
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new RuntimeException("[SSOptimizer] script failed with error", cause);
        }
    }

    private static DebugScript instantiate(final ClassLoader loader, final String className) {
        try {
            final Class<?> raw = Class.forName(className, true, loader);
            if (!DebugScript.class.isAssignableFrom(raw)) {
                throw new IllegalArgumentException(
                        "[SSOptimizer] Script class '" + className + "' does not implement DebugScript");
            }
            return DebugScript.class.cast(raw.getDeclaredConstructor().newInstance());
        } catch (final ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    "[SSOptimizer] className '" + className + "' not in compilation output", e);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                    "[SSOptimizer] Script class '" + className + "' must implement DebugScript "
                            + "with a public no-arg constructor: " + e, e);
        }
    }

    private static String requireParam(final JsonObject params, final String name) {
        if (params == null || !params.has(name) || !params.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException("[SSOptimizer] Missing required param: " + name);
        }
        return params.get(name).getAsString();
    }

    /**
     * 脚本隔离类加载器：字节来自内存编译产物，父加载器为 sso-debug 模块加载器
     * （生产环境即 LaunchClassLoader，游戏/模组类经双亲委派可见）。
     */
    private static final class ScriptClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private ScriptClassLoader(final Map<String, byte[]> classes, final ClassLoader parent) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final byte[] bytes = classes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
