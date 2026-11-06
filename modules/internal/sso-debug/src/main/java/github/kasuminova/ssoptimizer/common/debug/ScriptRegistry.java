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

public final class ScriptRegistry {
    private static final Logger LOGGER = Logger.getLogger(ScriptRegistry.class);
    private static final Gson GSON = new Gson();

    private final ScriptCompiler compiler = new ScriptCompiler();
    private final Map<String, ScriptHandle> handles = new ConcurrentHashMap<>();
    private final java.nio.file.Path outputDir;

    public ScriptRegistry(final java.nio.file.Path outputDir) {
        this.outputDir = outputDir;
    }

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

    public List<ScriptHandle> list() {
        return List.copyOf(handles.values());
    }

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
        router.register("debug_stats", params -> {
            final JsonObject result = new JsonObject();
            result.addProperty("drainCount", MainThreadTasks.drainCount());
            result.addProperty("scripts", handles.size());
            return result;
        });
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
