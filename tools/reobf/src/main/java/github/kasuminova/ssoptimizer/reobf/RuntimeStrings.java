package github.kasuminova.ssoptimizer.reobf;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

final class RuntimeStrings {
    private static final String PROJECT = "github/kasuminova/ssoptimizer/";
    private static final String GAME = "com/fs/starfarer/";
    private static final String LAUNCHER = GAME + "StarfarerLauncher";
    private static final String COLLISION = GAME + "combat/CollisionHandlerImpl";
    private static final String SHIP = GAME + "combat/entities/Ship";
    private static final String SHIELD = GAME + "combat/systems/Shield";
    private static final String PROFILER = "com/fs/profiler/Profiler";
    private static final String DAMAGE_RESULT = GAME + "combat/entities/ship/ApplyDamageResult";
    private static final String COLLISION_ENTITY = "L" + GAME + "combat/CollisionEntity;";
    private static final String DAMAGE = "L" + GAME + "combat/Damage;";
    private static final String VECTOR = "Lorg/lwjgl/util/vector/Vector2f;";
    private static final String COLLISION_DESC = "(" + COLLISION_ENTITY + COLLISION_ENTITY + VECTOR + ")F";
    private static final String SHIP_DESC = "(" + VECTOR + DAMAGE + "ZZFLjava/lang/Object;)L" + DAMAGE_RESULT + ";";
    private static final String CLAMP_DESC = "(F" + COLLISION_ENTITY + COLLISION_ENTITY + ")Z";
    private static final String SHIELD_GUARD_DESC = "(FF" + DAMAGE + "FLjava/lang/Object;Ljava/lang/Object;)Z";
    private static final String ARMOR_GUARD_DESC = "(FFF" + DAMAGE + "FLjava/lang/Object;Ljava/lang/Object;)Z";

    private static final String PROCESSOR = "([B)[B";
    private static final String VISIT_METHOD = "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)Lorg/objectweb/asm/MethodVisitor;";
    private static final String VISIT_VAR_INSN = "(II)V";
    private static final String REGISTER_PROCESSORS = "(Ljava/util/function/BiConsumer;Ljava/util/function/BiConsumer;)V";
    private static final List<String> CLASS_LOGGERS = List.of(
            "com.fs.starfarer.loading.LoadingUtils",
            "com.fs.starfarer.loading.SpecStore",
            "com.fs.starfarer.loading.WeaponSpreadsheetLoader",
            "com.fs.starfarer.loading.WeaponSpecLoader",
            "com.fs.starfarer.loading.ShipHullSpreadsheetLoader",
            "com.fs.starfarer.loading.ShipHullSpecLoader",
            "com.fs.starfarer.loading.FighterWingSpreadsheetLoader",
            "com.fs.starfarer.loading.HullVariantSpecStore",
            "com.fs.starfarer.loading.ShipNameStore",
            "com.fs.starfarer.loading.scripts.ScriptStore",
            "com.fs.starfarer.loading.scripts.ScriptClassLoader",
            "com.fs.starfarer.campaign.rules.Rules",
            "com.fs.graphics.TextureLoader",
            "sound.Sound",
            "sound.Music",
            "com.fs.starfarer.launcher.ModManager",
            "com.fs.starfarer.api.impl.codex.CodexTextEntryLoader");

    private static final Pattern EMBEDDED_TYPE = Pattern.compile("L([\\p{L}\\p{N}_$/]+);");
    private static final Pattern ASM_METHOD = Pattern.compile("M[0-9]+:([^ .]+)\\.([^ (]+)\\(.*");
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*");

    private final ClassNode node;
    private final GameClasses game;
    private final ReobfMappings mappings;
    private final Set<Object> handled = Collections.newSetFromMap(new IdentityHashMap<>());

    private RuntimeStrings(ClassNode node, ReobfMappings mappings, GameClasses game) {
        this.node = node;
        this.mappings = mappings;
        this.game = game;
    }

    static void rewrite(ClassNode node, ReobfMappings mappings, GameClasses game) {
        new RuntimeStrings(node, mappings, game).rewrite();
    }

    private void rewrite() {
        final UnaryOperator<String> type = this::gameClass;
        final UnaryOperator<String> descriptor = this::descriptor;
        switch (node.name) {
            case PROJECT + "bootstrap/SharedBootstrap" -> {
                for (String owner : List.of(LAUNCHER, SHIP, COLLISION)) {
                    operand("registerEngineProcessors", REGISTER_PROCESSORS, owner, type.apply(owner), 1);
                }
            }
            case PROJECT + "asm/launcher/LauncherDirectStartProcessor" -> targetClass(LAUNCHER);
            case PROJECT + "asm/combat/CollisionImpulseClampProcessor" -> {
                targetClass(COLLISION);
                field("TARGET_METHOD", "applyCollisionImpulse", gameMethod(COLLISION, "applyCollisionImpulse", COLLISION_DESC));
                field("TARGET_DESC", COLLISION_DESC, descriptor.apply(COLLISION_DESC));
                field("CLAMP_DESC", CLAMP_DESC, descriptor.apply(CLAMP_DESC));
            }
            case PROJECT + "asm/combat/CollisionImpulseClampProcessor$2", PROJECT + "asm/combat/CollisionImpulseClampProcessor$3" ->
                    methodMatcher(COLLISION, "applyCollisionImpulse", COLLISION_DESC);
            case PROJECT + "asm/combat/CollisionImpulseClampProcessor$2$1" ->
                    operand("visitVarInsn", VISIT_VAR_INSN, CLAMP_DESC, descriptor.apply(CLAMP_DESC), 1);
            case PROJECT + "asm/combat/ShipDamageStageTwoProcessor" -> {
                targetClass(SHIP);
                field("TARGET_METHOD", "applyDamageInner", gameMethod(SHIP, "applyDamageInner", SHIP_DESC));
                field("TARGET_DESC", SHIP_DESC, descriptor.apply(SHIP_DESC));
                field("SHIELD_GUARD_DESC", SHIELD_GUARD_DESC, descriptor.apply(SHIELD_GUARD_DESC));
                field("ARMOR_GUARD_DESC", ARMOR_GUARD_DESC, descriptor.apply(ARMOR_GUARD_DESC));
                field("PROFILER_OWNER", PROFILER, type.apply(PROFILER));
                field("RESULT_CLASS", DAMAGE_RESULT, type.apply(DAMAGE_RESULT));
                field("SHIELD_OWNER", SHIELD, type.apply(SHIELD));
                field("FLUX_PER_POINT", "getFluxPerPointOfDamage", gameMethod(SHIELD, "getFluxPerPointOfDamage", "()F"));
            }
            case PROJECT + "asm/combat/ShipDamageStageTwoProcessor$2", PROJECT + "asm/combat/ShipDamageStageTwoProcessor$3" ->
                    methodMatcher(SHIP, "applyDamageInner", SHIP_DESC);
            case PROJECT + "asm/combat/ShipDamageStageTwoProcessor$AnchorWindow" -> {
                final String prefix = "M" + Opcodes.INVOKEVIRTUAL + ":";
                final String anchorName = gameMethod(SHIELD, "getFluxPerPointOfDamage", "()F");
                operand("visitVarInsn", VISIT_VAR_INSN, prefix + SHIELD + ".getFluxPerPointOfDamage()F",
                        prefix + type.apply(SHIELD) + "." + anchorName + "()F", 1);
            }
            case PROJECT + "asm/combat/ShipDamageStageTwoProcessor$StageTwoMethodAdapter" -> {
                operand("onShieldAnchor", "()V", SHIELD_GUARD_DESC, descriptor.apply(SHIELD_GUARD_DESC), 1);
                operand("onArmorAnchor", "()V", ARMOR_GUARD_DESC, descriptor.apply(ARMOR_GUARD_DESC), 1);
                final String emitDesc = "(Ljava/lang/String;Ljava/lang/String;[I)V";
                operand("emitGuard", emitDesc, PROFILER, type.apply(PROFILER), 1);
                operand("emitGuard", emitDesc, "end", gameMethod(PROFILER, "end", "()V"), 1);
                operand("emitGuard", emitDesc, DAMAGE_RESULT, type.apply(DAMAGE_RESULT), 2);
            }
            case PROJECT + "common/logging/VanillaLogNoiseConfigurator" -> {
                for (String logger : CLASS_LOGGERS) {
                    operand("<clinit>", "()V", logger, binaryName(logger), 1);
                }
            }
            case "ssoptimizer/standalone/host/StandaloneTweaker" -> {
                final String launcher = LAUNCHER.replace('/', '.');
                operand("getLaunchTarget", "()Ljava/lang/String;", launcher, binaryName(launcher), 1);
            }
            default -> { }
        }
        audit();
    }

    private String binaryName(String name) {
        return gameClass(name.replace('.', '/')).replace('/', '.');
    }

    private String gameClass(String name) {
        final String mapped = mappings.className(name);
        if (!game.contains(mapped)) {
            throw new IllegalArgumentException(node.name + " 引用的游戏类不存在 " + name + " -> " + mapped);
        }
        return mapped;
    }

    private String gameMethod(String owner, String name, String desc) {
        final String targetOwner = gameClass(owner);
        final String targetName = mappings.methodName(owner, name, desc);
        final String targetDesc = descriptor(desc);
        if (!game.hasMember(targetOwner, targetName, targetDesc, true)) {
            throw new IllegalArgumentException(node.name + " 引用的游戏方法不存在 " + targetOwner + "." + targetName + targetDesc);
        }
        return targetName;
    }

    private String descriptor(String desc) {
        final var types = EMBEDDED_TYPE.matcher(desc);
        while (types.find()) {
            final String name = types.group(1);
            if (gameType(name)) gameClass(name);
        }
        return mappings.descriptor(desc);
    }

    private void targetClass(String name) {
        final String mapped = gameClass(name);
        field("TARGET_CLASS", name, mapped);
        operand("process", PROCESSOR, name, mapped, 1);
    }

    private void methodMatcher(String owner, String name, String desc) {
        operand("visitMethod", VISIT_METHOD, name, gameMethod(owner, name, desc), 1);
        operand("visitMethod", VISIT_METHOD, desc, descriptor(desc), 1);
    }

    private void field(String fieldName, String from, String to) {
        final var field = node.fields.stream().filter(f -> f.name.equals(fieldName)).findFirst().orElseThrow(
                () -> new IllegalArgumentException(node.name + "." + fieldName + " 缺少符号字段"));
        if (!from.equals(field.value)) {
            throw new IllegalArgumentException(node.name + "." + fieldName + " 的符号值应为 " + from + "，实际为 " + field.value);
        }
        field.value = to;
        handled.add(field);
    }

    private void operand(String methodName, String desc, String from, String to, int expected) {
        final var method = node.methods.stream().filter(m -> m.name.equals(methodName) && m.desc.equals(desc)).findFirst().orElseThrow(
                () -> new IllegalArgumentException(node.name + "." + methodName + desc + " 缺少符号使用位置"));
        int matches = 0;
        for (var insn : method.instructions) {
            if (insn instanceof LdcInsnNode literal && from.equals(literal.cst)) {
                literal.cst = to;
                handled.add(literal);
                matches++;
            }
        }
        if (matches != expected) {
            throw new IllegalArgumentException(node.name + "." + methodName + desc + " 的符号 " + from + " 应出现 " + expected + " 次，实际出现 " + matches + " 次");
        }
    }

    private void audit() {
        for (var field : node.fields) {
            if (!handled.contains(field) && field.value instanceof String text) verifyLiteral("#" + field.name, text);
        }
        for (var method : node.methods) {
            final String site = method.name + method.desc;
            for (var insn : method.instructions) {
                if (insn instanceof LdcInsnNode literal && !handled.contains(literal)) {
                    verifyConstant(site, literal.cst);
                } else if (insn instanceof InvokeDynamicInsnNode dynamic) {
                    for (Object argument : dynamic.bsmArgs) verifyConstant(site, argument);
                }
            }
        }
    }

    private void verifyConstant(String site, Object constant) {
        if (constant instanceof String text) {
            verifyLiteral(site, text);
        } else if (constant instanceof ConstantDynamic dynamic) {
            for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
                verifyConstant(site, dynamic.getBootstrapMethodArgument(i));
            }
        }
    }

    private void verifyLiteral(String site, String text) {
        if (gameType(text) || containsGameType(EMBEDDED_TYPE, text) || containsGameType(ASM_METHOD, text)
                || isUnregisteredMember(text)) {
            throw new IllegalArgumentException(node.name + "." + site + " 含未登记的游戏符号 " + text);
        }
    }

    private boolean isUnregisteredMember(String name) {
        if (!IDENTIFIER.matcher(name).matches()) return false;
        final String owner = node.name;
        final List<String> targets;
        if (owner.startsWith(PROJECT + "asm/combat/CollisionImpulseClampProcessor")) {
            targets = List.of(COLLISION);
        } else if (owner.startsWith(PROJECT + "asm/combat/ShipDamageStageTwoProcessor")) {
            targets = List.of(SHIP, SHIELD, PROFILER, DAMAGE_RESULT);
        } else if (owner.startsWith(PROJECT + "asm/launcher/LauncherDirectStartProcessor")) {
            targets = List.of(LAUNCHER);
        } else {
            return false;
        }
        for (String target : targets) {
            if (mappings.mappedNames(target, name, null, true).stream().anyMatch(mapped -> !mapped.equals(name))
                    || mappings.mappedNames(target, name, null, false).stream().anyMatch(mapped -> !mapped.equals(name))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsGameType(Pattern pattern, String text) {
        final var types = pattern.matcher(text);
        while (types.find()) {
            if (gameType(types.group(1))) return true;
        }
        return false;
    }

    private boolean gameType(String name) {
        final String internalName = name.replace('.', '/');
        return mappings.isGameClass(internalName) || game.contains(internalName);
    }
}
