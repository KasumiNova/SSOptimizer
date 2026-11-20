package ssoptimizer.qa;

import net.minecraft.launchwrapper.LaunchClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.objectweb.asm.Opcodes.INVOKESTATIC;

final class ObfMixinChecks {
    private static final String PROJECT = "github/kasuminova/ssoptimizer/";
    private static final String PACKAGE = "github.kasuminova.ssoptimizer.";
    private static final String MIXINS = PACKAGE + "mixin.";
    private static final String COMBAT_GUARD = PROJECT + "common/combat/CombatNaNGuard";
    private final Map<String, ClassNode> before = new HashMap<>();
    private final Map<String, ClassNode> after = new HashMap<>();
    private final List<Anchor> anchors = new ArrayList<>();
    private final Map<String, Set<String>> declared;
    private final Set<String> targets = new TreeSet<>();
    private final boolean split;

    ObfMixinChecks(LaunchClassLoader loader, Map<String, Set<String>> declared, boolean split) throws IOException {
        this.declared = declared;
        this.split = split;
        targets.addAll(declared.keySet());
        anchor(loader, "asm.launcher.LauncherDirectStartProcessor", null, "HELPER_METHOD", "HELPER_DESC");
        anchor(loader, "asm.combat.CollisionImpulseClampProcessor", COMBAT_GUARD, "CLAMP_METHOD", "CLAMP_DESC");
        anchor(loader, "asm.combat.ShipDamageStageTwoProcessor", COMBAT_GUARD, "SHIELD_GUARD_METHOD", "SHIELD_GUARD_DESC");
        anchor(loader, "asm.combat.ShipDamageStageTwoProcessor", COMBAT_GUARD, "ARMOR_GUARD_METHOD", "ARMOR_GUARD_DESC");
        loader.rfb$getMutableTransformers().add((name, transformedName, bytes) -> {
            if (bytes != null && targets.contains(transformedName)) after.put(transformedName, read(bytes, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES));
            return bytes;
        });
    }

    Set<String> targets() { return targets; }

    void beforeMixin(ClassNode source) {
        var snapshot = new ClassNode();
        source.accept(snapshot);
        before.put(source.name.replace('/', '.'), snapshot);
    }

    List<Result> verify(Map<String, Set<String>> merged, List<ObfMixinAudit.HandlerCount> handlers) {
        var result = new ArrayList<Result>();
        for (var anchor : anchors) {
            int actual = calls(after.get(anchor.target), Set.of(anchor.method), anchor.owner, anchor.name, anchor.desc, true);
            result.add(new Result("asm", anchor.target, anchor.method + " -> " + anchor.owner + "." + anchor.name + anchor.desc, "1", actual, actual == 1));
        }
        mixin(result, merged, "loading.TextureLoaderUploadMixin", split);
        mixin(result, merged, "loading.TextureLoaderUploadDirectMixin", !split);
        mixin(result, merged, "render.CombatStateTraversalMixin", split);
        mixin(result, merged, "render.CombatStateTraversalDirectMixin", !split);
        mixin(result, merged, "render.SpriteMixin", split);
        gl(result, "bench.CombatStateBenchmarkMixin", Set.of("traverse"), "Display", "update", "(Z)V");
        gl(result, "render.SpriteAtlasMixin", Set.of("renderNoBlendOrRotate", "renderAtCenterWithCornerColors"), "GL11", "glTexCoord2f", "(FF)V");
        handler(result, handlers, "bench.CombatStateBenchmarkMixin", "ssoptimizer$onCombatFrameEnd", !split);
        handler(result, handlers, "bench.CombatStateBenchmarkMixin", "ssoptimizer$onCombatFrameEndBridged", split);
        handler(result, handlers, "render.SpriteAtlasMixin", "ssoptimizer$texCoordWithAtlasOrigin", !split);
        handler(result, handlers, "render.SpriteAtlasMixin", "ssoptimizer$texCoordWithAtlasOriginBridged", split);
        return result;
    }

    private void anchor(LaunchClassLoader loader, String processor, String helperOwner, String helperMethod, String helperDesc) throws IOException {
        String type = PACKAGE + processor;
        byte[] bytes = loader.getClassBytes(type);
        if (bytes == null) throw new IOException("缺少 ASM 处理器 " + type);
        var node = read(bytes, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        String target = constant(node, "TARGET_CLASS").replace('/', '.');
        String method = constant(node, "TARGET_METHOD") + constant(node, "TARGET_DESC");
        anchors.add(new Anchor(target, method, helperOwner == null ? constant(node, "HELPER_OWNER") : helperOwner,
                constant(node, helperMethod), constant(node, helperDesc)));
        targets.add(target);
    }

    private void mixin(List<Result> result, Map<String, Set<String>> merged, String mixin, boolean expected) {
        String target = target(mixin);
        int actual = merged.getOrDefault(target, Set.of()).contains(MIXINS + mixin) ? 1 : 0;
        result.add(new Result("mixin", target, mixin, expected ? "1" : "0", actual, (actual == 1) == expected));
    }

    private void gl(List<Result> result, String mixin, Set<String> methods, String owner, String name, String desc) {
        String target = target(mixin);
        ClassNode node = before.get(target);
        for (boolean bridge : List.of(false, true)) {
            String actualOwner = bridge ? PROJECT + "bridge/opengl/" + owner : "org/lwjgl/opengl/" + owner;
            int actual = calls(node, methods, actualOwner, name, desc, false);
            boolean expected = bridge == split;
            result.add(new Result("gl-before-mixin", target, actualOwner + "." + name + desc, expected ? ">0" : "0", actual,
                    node != null && (actual > 0) == expected));
        }
    }

    private void handler(List<Result> result, List<ObfMixinAudit.HandlerCount> handlers, String mixin, String name, boolean expected) {
        String target = target(mixin);
        int count = handlers.stream().filter(handler -> handler.target().equals(target) && handler.mixin().equals(MIXINS + mixin)
                && handler.original().equals(name)).mapToInt(ObfMixinAudit.HandlerCount::gameCalls).sum();
        result.add(new Result("injection", target, mixin + "." + name, expected ? ">0" : "0", count,
                !target.isEmpty() && (count > 0) == expected));
    }

    private String target(String mixin) {
        return declared.entrySet().stream().filter(entry -> entry.getValue().contains(MIXINS + mixin))
                .map(Map.Entry::getKey).findFirst().orElse("");
    }

    private static String constant(ClassNode node, String name) throws IOException {
        for (var field : node.fields) if (field.name.equals(name) && field.value instanceof String value) return value;
        throw new IOException("缺少 ASM 处理器常量 " + node.name + "." + name);
    }

    private static ClassNode read(byte[] bytes, int flags) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, flags);
        return node;
    }

    private static int calls(ClassNode node, Set<String> methods, String owner, String name, String desc, boolean signature) {
        if (node == null) return 0;
        int count = 0;
        for (var method : node.methods) {
            if (!methods.contains(signature ? method.name + method.desc : method.name)) continue;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)
                        && call.desc.equals(desc) && call.getOpcode() == INVOKESTATIC) count++;
            }
        }
        return count;
    }

    record Result(String stage, String target, String symbol, String expected, int actual, boolean passed) { }
    private record Anchor(String target, String method, String owner, String name, String desc) { }
}
