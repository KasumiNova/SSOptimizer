import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.SourceInterpreter;

import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

class TransformTargets {
    static final String BOOT = "github/kasuminova/ssoptimizer/bootstrap/";
    static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    final Set<String> known = new HashSet<>(), annotated = new HashSet<>(), configured = new TreeSet<>();
    final List<String> errors = new ArrayList<>();
    int targets, registrations;

    public static void main(String[] args) throws Exception {
        TransformTargetsRegression.run();
        var audit = new TransformTargets();
        int split = List.of(args).indexOf("--project");
        if (split <= 0 || split == args.length - 1) throw new IllegalArgumentException("缺少游戏 JAR 或项目模块");
        for (int i = 0; i < split; i++) try (var jar = new JarFile(args[i])) {
            jar.stream().map(e -> e.getName()).filter(n -> n.endsWith(".class")).forEach(n -> audit.known.add(n.substring(0, n.length() - 6)));
        }
        var classes = new ArrayList<Path>();
        for (int i = split + 1; i < args.length; i++) {
            Path root = Path.of(args[i]);
            try (var paths = Files.walk(root)) {
                for (var path : paths.filter(Files::isRegularFile).toList()) {
                    String name = root.relativize(path).toString().replace(java.io.File.separatorChar, (char)47);
                    if (name.endsWith(".class")) {
                        audit.known.add(name.substring(0, name.length() - 6));
                        classes.add(path);
                    } else if (name.endsWith(".json")) {
                        if (name.startsWith("mixins.") || name.endsWith(".mixins.json")) audit.config(path);
                    }
                }
            }
        }
        for (var path : classes) {
            var node = new ClassNode();
            new ClassReader(Files.readAllBytes(path)).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            audit.inspect(node);
        }
        for (var mixin : audit.configured) if (!audit.annotated.contains(mixin)) audit.errors.add("配置缺少已编译的 @Mixin 类: " + mixin);
        if (audit.configured.isEmpty() || audit.registrations == 0) audit.errors.add("缺少 Mixin 配置或 ASM 注册");
        if (!audit.errors.isEmpty()) throw new IllegalStateException(String.join("\n", audit.errors));
        System.out.printf("校验通过：%d 个配置内 Mixin，%d 个 Mixin 目标，%d 个静态 ASM 注册。%n", audit.configured.size(), audit.targets, audit.registrations);
    }

    void config(Path path) throws Exception {
        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        String pkg = json.get("package").getAsString();
        for (var side : List.of("mixins", "client", "server")) if (json.has(side)) {
            for (var entry : json.getAsJsonArray(side)) configured.add((pkg + "." + entry.getAsString()).replace(".", "/"));
        }
    }

    void check(String caller, String target) {
        if (!known.contains(target.replace(".", "/"))) errors.add(caller + ": 未声明的变换目标 " + target);
    }

    void inspect(ClassNode node) throws Exception {
        var annotations = new ArrayList<AnnotationNode>();
        if (node.visibleAnnotations != null) annotations.addAll(node.visibleAnnotations);
        if (node.invisibleAnnotations != null) annotations.addAll(node.invisibleAnnotations);
        for (var a : annotations) if (MIXIN.equals(a.desc)) {
            annotated.add(node.name);
            int count = 0;
            for (int i = 0; a.values != null && i < a.values.size(); i += 2) {
                if (!Set.of("targets", "value").contains(a.values.get(i))) continue;
                for (var v : (List<?>)a.values.get(i+1)) {
                    check(node.name, v instanceof Type t ? t.getInternalName() : (String)v);
                    count++;
                }
            }
            if (count == 0) errors.add(node.name + ": 缺少目标");
            targets += count;
        }
        for (var m : node.methods) {
            for (var insn : m.instructions) if (insn instanceof MethodInsnNode call) {
                int arg = argument(node.name, m.name, call);
                if (arg < 0) continue;
                var frames = new Analyzer<>(new SourceInterpreter()).analyze(node.name, m);
                var frame = frames[m.instructions.indexOf(call)];
                var source = frame.getStack(frame.getStackSize() - Type.getArgumentTypes(call.desc).length + arg);
                String caller = node.name + "." + m.name;
                for (var origin : source.insns) {
                    if (node.name.equals(BOOT + "SSOptimizerCorePlugin") && m.name.equals("registerSystem") &&
                        origin instanceof VarInsnNode v && v.getOpcode() == org.objectweb.asm.Opcodes.ALOAD && v.var == 0) continue;
                    if (origin instanceof LdcInsnNode ldc && ldc.cst instanceof String s) check(caller, s);
                    else errors.add(caller + ": ASM 注册参数需要使用编译期字符串常量");
                }
                if (source.insns.isEmpty()) errors.add(caller + ": ASM 目标来源未知");
                if (!node.name.equals(BOOT + "SSOptimizerCorePlugin") || !m.name.equals("registerSystem")) registrations++;
            }
        }
    }

    static int argument(String owner, String method, MethodInsnNode call) {
        if (owner.equals(BOOT + "SharedBootstrap") && method.equals("registerEngineProcessors") &&
            call.owner.equals("java/util/function/BiConsumer") && call.name.equals("accept")) return 0;
        if (call.owner.equals(BOOT + "SharedBootstrap") && Set.of("registerIf", "registerSystemIf").contains(call.name)) return 2;
        if (call.owner.equals(BOOT + "HybridWeaverTransformer") && call.name.equals("registerProcessor")) return 0;
        if (call.owner.endsWith("/SystemAsmBridge") && call.name.equals("register")) return 0;
        return -1;
    }
}
