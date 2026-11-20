package ssoptimizer.qa;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;
import org.spongepowered.asm.util.asm.MethodNodeEx;
import ssoptimizer.standalone.host.StandaloneTweaker;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class ObfMixinAudit {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    private static final List<Failure> FAILURES = new ArrayList<>();
    private static final Map<String, Snapshot> BEFORE = new HashMap<>();
    private static final Map<String, Snapshot> AFTER = new HashMap<>();
    private static final Map<String, Set<String>> MERGED_METHOD_MIXINS = new HashMap<>();
    private static final List<HandlerCount> HANDLERS = new ArrayList<>();
    private static List<IMixinConfig> configurations;
    private static ObfMixinChecks checks;

    private ObfMixinAudit() { }

    public static final class Tweaker implements ITweaker {
        private final StandaloneTweaker runtime = new StandaloneTweaker();

        @Override public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
            runtime.acceptOptions(args, gameDir, assetsDir, profile);
        }

        @Override public void injectIntoClassLoader(LaunchClassLoader loader) {
            loader.addClassLoaderExclusion("ssoptimizer.qa.");
            Mixins.registerErrorHandlerClass(Errors.class.getName());
            runtime.injectIntoClassLoader(loader);
            configurations = Mixins.getConfigs().stream().map(config -> config.getConfig()).toList();
        }

        @Override public String getLaunchTarget() {
            runtime.getLaunchTarget();
            var environment = MixinEnvironment.getCurrentEnvironment();
            var transformer = (IMixinTransformer) environment.getActiveTransformer();
            var extensions = (Extensions) transformer.getExtensions();
            extensions.add(new Observer());
            extensions.select(environment);
            return ObfMixinAudit.class.getName();
        }

        @Override public String[] getLaunchArguments() { return runtime.getLaunchArguments(); }
    }

    public static final class Errors implements IMixinErrorHandler {
        @Override public ErrorAction onPrepareError(IMixinConfig config, Throwable failure, IMixinInfo mixin, ErrorAction action) {
            FAILURES.add(new Failure("prepare", String.join(",", mixin.getTargetClasses()), config.getName() + ":" + mixin.getClassName(), detail(failure)));
            return action;
        }

        @Override public ErrorAction onApplyError(String target, Throwable failure, IMixinInfo mixin, ErrorAction action) {
            FAILURES.add(new Failure("apply", target, mixin.getConfig().getName() + ":" + mixin.getClassName(), detail(failure)));
            return action;
        }
    }

    private static final class Observer implements IExtension {
        private final Map<String, Set<String>> sourceHandlers = new HashMap<>();

        @Override public boolean checkActive(MixinEnvironment env) { return true; }

        @Override public void preApply(ITargetClassContext target) {
            var node = target.getClassNode();
            BEFORE.put(binary(node.name), snapshot(node));
            if (checks != null) checks.beforeMixin(node);
        }

        @Override public void postApply(ITargetClassContext target) {
            var node = target.getClassNode();
            String className = binary(node.name);
            AFTER.put(className, snapshot(node));
            Map<String, Integer> calls = new HashMap<>();
            Map<String, Integer> gameCalls = new HashMap<>();
            Set<String> origins = new TreeSet<>();
            for (var method : node.methods) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && call.owner.equals(node.name)) {
                        calls.merge(call.name + call.desc, 1, Integer::sum);
                        if (annotation(method.visibleAnnotations, MERGED) == null) gameCalls.merge(call.name + call.desc, 1, Integer::sum);
                    }
                }
            }
            for (var method : node.methods) {
                var marker = annotation(method.visibleAnnotations, MERGED);
                if (marker != null && value(marker, "mixin") instanceof String mixin) origins.add(mixin);
                if (!(method instanceof MethodNodeEx merged)) continue;
                IMixinInfo mixin = merged.getOwner();
                if (mixin == null) continue;
                origins.add(mixin.getClassName());
                String original = merged.getOriginalName();
                if (!sourceHandlers.computeIfAbsent(mixin.getClassName(), unused -> injectorNames(mixin)).contains(original)) continue;
                String handler = method.name + method.desc;
                HANDLERS.add(new HandlerCount(className, mixin.getClassName(), original, handler, calls.getOrDefault(handler, 0), gameCalls.getOrDefault(handler, 0)));
            }
            MERGED_METHOD_MIXINS.put(className, origins);
        }

        private static Set<String> injectorNames(IMixinInfo mixin) {
            var names = new HashSet<String>();
            for (var method : mixin.getClassNode(ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES).methods) {
                if (InjectionInfo.getInjectorAnnotation(mixin, method) != null) names.add(MethodNodeEx.getName(method));
            }
            return names;
        }

        @Override public void export(MixinEnvironment env, String name, boolean force, ClassNode node) { }
    }

    public static void main(String[] args) throws IOException {
        Path output = Path.of(System.getProperty("ssoptimizer.audit.out"));
        var environment = MixinEnvironment.getCurrentEnvironment();
        LaunchClassLoader loader = Launch.classLoader;
        var declarations = declarations(loader, environment.getSide());
        Map<String, String> results = new LinkedHashMap<>();
        checks = new ObfMixinChecks(loader, declarations, Boolean.getBoolean("ssoptimizer.renderthread.enable"));
        Set<String> candidates = checks.targets();
        for (String name : candidates) {
            try {
                byte[] bytes = loader.getClassBytes(name);
                if (bytes == null) {
                    if (game(name)) FAILURES.add(new Failure("target", name, String.join(",", declarations.getOrDefault(name, Set.of())), "游戏目标类不存在"));
                    results.put(name, game(name) ? "missing" : "external-missing");
                    continue;
                }
                Class<?> defined = Class.forName(name, false, loader);
                if (game(name) && defined.getClassLoader() != loader) {
                    throw new LinkageError("游戏目标由错误的加载器定义: " + defined.getClassLoader());
                }
                results.put(name, "loaded");
            } catch (ClassNotFoundException | IOException | RuntimeException | LinkageError failure) {
                results.put(name, "failed");
                FAILURES.add(new Failure("load", name, "", detail(failure)));
                failure.printStackTrace(System.err);
            }
        }
        Map<String, Set<String>> selected = new TreeMap<>();
        for (IMixinConfig config : configurations) {
            for (String name : config.getTargets()) selected.computeIfAbsent(binary(name), key -> new TreeSet<>()).add(config.getName());
        }
        for (var entry : selected.entrySet()) {
            String name = entry.getKey();
            if (!game(name) && !declarations.containsKey(name)) continue;
            if (!AFTER.containsKey(name) && !"failed".equals(results.get(name)) && !"missing".equals(results.get(name))) {
                FAILURES.add(new Failure("apply", name, String.join(",", entry.getValue()), "Mixin 选中此目标但未完成 postApply"));
            }
            if (!declarations.containsKey(name)) {
                FAILURES.add(new Failure("target", name, String.join(",", entry.getValue()), "运行时选中未列入配置声明的游戏目标"));
            }
        }
        try (var writer = Files.newBufferedWriter(output.resolve("checks.tsv"))) {
            row(writer, "stage", "target", "symbol", "expected", "actual", "passed");
            for (var check : checks.verify(MERGED_METHOD_MIXINS, HANDLERS)) {
                row(writer, check.stage(), check.target(), check.symbol(), check.expected(), Integer.toString(check.actual()), Boolean.toString(check.passed()));
                if (!check.passed()) FAILURES.add(new Failure(check.stage(), check.target(), check.symbol(),
                        "期望 " + check.expected() + "，实际 " + check.actual()));
            }
        }
        long gameTargets = declarations.keySet().stream().filter(ObfMixinAudit::game).count();
        long applied = AFTER.keySet().stream().filter(ObfMixinAudit::game).count();
        if (gameTargets == 0 || selected.isEmpty()) FAILURES.add(new Failure("prepare", "", "", "未发现已配置并选择的游戏目标"));
        try (var writer = Files.newBufferedWriter(output.resolve("targets.tsv"))) {
            row(writer, "kind", "target", "status", "declared_mixins", "selected_configs", "merged_method_mixins", "before_sha256", "after_sha256", "before_methods", "after_methods");
            for (var entry : results.entrySet()) {
                String name = entry.getKey();
                var actual = MERGED_METHOD_MIXINS.getOrDefault(name, Set.of());
                var before = BEFORE.get(name);
                var after = AFTER.get(name);
                String status = entry.getValue();
                if (status.equals("loaded")) status = after != null ? "applied" : declarations.containsKey(name) ? "skipped" : "asm-only";
                row(writer, game(name) ? "game" : "library", name, status, String.join(",", declarations.getOrDefault(name, Set.of())),
                        String.join(",", selected.getOrDefault(name, Set.of())), String.join(",", actual),
                        before == null ? "" : before.sha256, after == null ? "" : after.sha256,
                        before == null ? "" : Integer.toString(before.methods), after == null ? "" : Integer.toString(after.methods));
            }
        }
        try (var writer = Files.newBufferedWriter(output.resolve("handlers.tsv"))) {
            row(writer, "target", "mixin", "original_handler", "woven_handler", "direct_calls", "game_method_calls");
            for (var handler : HANDLERS) row(writer, handler.target, handler.mixin, handler.original, handler.woven, Integer.toString(handler.calls), Integer.toString(handler.gameCalls));
        }
        try (var writer = Files.newBufferedWriter(output.resolve("failures.tsv"))) {
            row(writer, "stage", "target", "mixin", "error");
            for (var failure : new LinkedHashSet<>(FAILURES)) row(writer, failure.stage, failure.target, failure.mixin, failure.details);
        }
        try (var writer = Files.newBufferedWriter(output.resolve("summary.tsv"))) {
            row(writer, "render_thread", "mixin_side", "configurations", "game_targets", "game_post_apply", "handler_rows", "errors");
            row(writer, System.getProperty("ssoptimizer.renderthread.enable"), environment.getSide().name(), Integer.toString(configurations.size()),
                    Long.toString(gameTargets), Long.toString(applied), Integer.toString(HANDLERS.size()), Integer.toString(new LinkedHashSet<>(FAILURES).size()));
        }
        if (!FAILURES.isEmpty()) System.exit(1);
        System.exit(0);
    }

    private static Map<String, Set<String>> declarations(LaunchClassLoader loader, MixinEnvironment.Side side) throws IOException {
        var targets = new TreeMap<String, Set<String>>();
        for (IMixinConfig config : configurations) {
            try (InputStream in = loader.getResourceAsStream(config.getName())) {
                if (in == null) throw new IOException("无法读取已启用的 Mixin 配置 " + config.getName());
                JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                if (json.has("refmap") && !json.get("refmap").isJsonNull() && !json.get("refmap").getAsString().isBlank()) {
                    throw new IOException("obf Mixin 配置声明了 refmap: " + config.getName());
                }
                var lists = new ArrayList<>(List.of("mixins"));
                if (side == MixinEnvironment.Side.CLIENT) lists.add("client");
                if (side == MixinEnvironment.Side.SERVER) lists.add("server");
                for (String key : lists) {
                    if (!json.has(key)) continue;
                    for (var member : json.getAsJsonArray(key)) {
                        String mixin = config.getMixinPackage() + member.getAsString();
                        byte[] bytes = loader.getClassBytes(mixin);
                        if (bytes == null) throw new IOException(config.getName() + " 缺少 Mixin 类 " + mixin);
                        var node = new ClassNode();
                        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        var mark = annotation(node.invisibleAnnotations, MIXIN);
                        if (mark == null) throw new IOException("缺少 @Mixin: " + mixin);
                        addTargets(targets, mark, "value", mixin);
                        addTargets(targets, mark, "targets", mixin);
                    }
                }
            }
        }
        return targets;
    }

    private static void addTargets(Map<String, Set<String>> targets, AnnotationNode marker, String key, String mixin) {
        if (value(marker, key) instanceof List<?> entries) {
            for (Object entry : entries) {
                String name = entry instanceof Type type ? type.getClassName() : binary((String) entry);
                targets.computeIfAbsent(name, ignored -> new TreeSet<>()).add(mixin);
            }
        }
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String desc) {
        if (annotations != null) for (var annotation : annotations) if (annotation.desc.equals(desc)) return annotation;
        return null;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values != null) {
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
            }
        }
        return null;
    }

    private static Snapshot snapshot(ClassNode node) {
        var writer = new ClassWriter(0);
        node.accept(writer);
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(writer.toByteArray());
            return new Snapshot(HexFormat.of().formatHex(digest), node.methods.size());
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }

    private static boolean game(String name) { return name.startsWith("com.fs.") || name.startsWith("zzz.com.fs.") || name.startsWith("sound."); }
    private static String binary(String name) { return name.replace('/', '.'); }

    private static String detail(Throwable failure) {
        var reasons = new ArrayList<String>();
        for (Throwable cause = failure; cause != null && cause != cause.getCause(); cause = cause.getCause()) reasons.add(cause.toString());
        return String.join(" | ", reasons);
    }

    private static void row(java.io.Writer writer, String... columns) throws IOException {
        for (int i = 0; i < columns.length; i++) {
            if (i != 0) writer.write('\t');
            writer.write(columns[i].replace('\n', ' ').replace('\r', ' ').replace('\t', ' '));
        }
        writer.write('\n');
    }

    private record Failure(String stage, String target, String mixin, String details) { }
    private record Snapshot(String sha256, int methods) { }
    record HandlerCount(String target, String mixin, String original, String woven, int calls, int gameCalls) { }
}
