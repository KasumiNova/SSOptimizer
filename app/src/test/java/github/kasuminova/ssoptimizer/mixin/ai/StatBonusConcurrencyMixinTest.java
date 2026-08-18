package github.kasuminova.ssoptimizer.mixin.ai;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StatBonus/FiringSolutionEval 并发快照守卫的注入配置正确性验证（ASM 解析
 * 真实字节码，模式同 AiUtilsEscortTargetGuardMixinTest）：
 * 游戏侧确认 @Redirect 的调用点（recompute 内 values()、computeIncoming
 * WeaponDamage 内 getGroups/getWeapons）存在；Mixin 侧确认处理器 private
 * static 且 @Redirect 的 method/target/remap 配置一致。
 */
class StatBonusConcurrencyMixinTest {

    @Test
    void statBonusRecomputeIteratesLinkedHashMapValues() throws IOException {
        ClassNode node = readClass(GameClassNames.STAT_BONUS);
        MethodNode recompute = findMethod(node, "recompute");
        assertNotNull(recompute, "StatBonus.recompute 必须存在（注入点）");
        assertTrue(hasInvoke(recompute, "java/util/LinkedHashMap", "values"),
                "recompute 必须遍历 LinkedHashMap.values()");
    }

    @Test
    void statBonusHandlerIsPrivateStaticWithMatchingRedirect() throws IOException {
        ClassNode node = readClass("github/kasuminova/ssoptimizer/mixin/ai/StatBonusConcurrencyMixin");
        MethodNode handler = findMethod(node, "ssoptimizer$snapshotStatMods");
        assertNotNull(handler, "StatBonus 快照处理器必须存在");
        assertPrivateStatic(handler);

        AnnotationNode redirect = findAnnotation(handler, "Lorg/spongepowered/asm/mixin/injection/Redirect;");
        assertNotNull(redirect, "处理器必须带 @Redirect 注解");
        assertRedirectMethod(redirect, "recompute");
        assertRedirectTarget(redirect,
                "Ljava/util/LinkedHashMap;values()Ljava/util/Collection;");
    }

    @Test
    void firingSolutionEvalIteratesEnemyWeaponsAndGroups() throws IOException {
        ClassNode node = readClass(GameClassNames.FIRING_SOLUTION_EVAL);
        MethodNode method = findMethod(node, "computeIncomingWeaponDamage");
        assertNotNull(method, "computeIncomingWeaponDamage 必须存在（注入点）");
        assertTrue(hasInvoke(method, "com/fs/starfarer/combat/entities/Ship", "getGroups"),
                "方法必须遍历敌船 getGroups()");
        assertTrue(hasInvoke(method, "com/fs/starfarer/combat/systems/WeaponGroup", "getWeapons"),
                "方法必须遍历武器组 getWeapons()");
    }

    @Test
    void firingSolutionEvalHandlersArePrivateStaticWithMatchingRedirect() throws IOException {
        ClassNode node = readClass(
                "github/kasuminova/ssoptimizer/mixin/ai/FiringSolutionEvalConcurrencyMixin");

        MethodNode groups = findMethod(node, "ssoptimizer$snapshotWeaponGroups");
        assertNotNull(groups, "武器组快照处理器必须存在");
        assertPrivateStatic(groups);
        assertRedirectMethod(findAnnotation(groups, "Lorg/spongepowered/asm/mixin/injection/Redirect;"),
                "computeIncomingWeaponDamage");
        assertRedirectTarget(findAnnotation(groups, "Lorg/spongepowered/asm/mixin/injection/Redirect;"),
                "Lcom/fs/starfarer/combat/entities/Ship;getGroups()Ljava/util/List;");

        MethodNode weapons = findMethod(node, "ssoptimizer$snapshotWeapons");
        assertNotNull(weapons, "武器快照处理器必须存在");
        assertPrivateStatic(weapons);
        assertRedirectTarget(findAnnotation(weapons, "Lorg/spongepowered/asm/mixin/injection/Redirect;"),
                "Lcom/fs/starfarer/combat/systems/WeaponGroup;getWeapons()Ljava/util/List;");
    }

    private static boolean hasInvoke(MethodNode method, String owner, String name) {
        for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)) {
                return true;
            }
        }
        return false;
    }

    private static void assertPrivateStatic(MethodNode handler) {
        assertTrue((handler.access & Opcodes.ACC_PRIVATE) != 0
                        && (handler.access & Opcodes.ACC_STATIC) != 0,
                "处理器必须为 private static（Mixin 运行时校验硬性要求）");
    }

    private static void assertRedirectMethod(AnnotationNode redirect, String expectedMethod) {
        assertNotNull(redirect, "处理器必须带 @Redirect 注解");
        List<String> methods = new ArrayList<>();
        boolean remap = true;
        for (int i = 0; i + 1 < redirect.values.size(); i += 2) {
            Object key = redirect.values.get(i);
            Object value = redirect.values.get(i + 1);
            if ("method".equals(key) && value instanceof List<?> list) {
                for (Object entry : list) {
                    methods.add(String.valueOf(entry));
                }
            } else if ("remap".equals(key)) {
                remap = Boolean.TRUE.equals(value);
            }
        }
        assertEquals(List.of(expectedMethod), methods, "@Redirect.method 必须指向目标方法");
        assertTrue(!remap, "@Redirect 必须 remap=false");
    }

    private static void assertRedirectTarget(AnnotationNode redirect, String expectedTarget) {
        assertNotNull(redirect, "处理器必须带 @Redirect 注解");
        String target = null;
        for (int i = 0; i + 1 < redirect.values.size(); i += 2) {
            Object key = redirect.values.get(i);
            if (!"at".equals(key)) {
                continue;
            }
            Object value = redirect.values.get(i + 1);
            AnnotationNode atNode = null;
            if (value instanceof AnnotationNode node) {
                atNode = node;
            } else if (value instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof AnnotationNode node) {
                atNode = node;
            }
            if (atNode != null && atNode.values != null) {
                for (int j = 0; j + 1 < atNode.values.size(); j += 2) {
                    if ("target".equals(atNode.values.get(j))) {
                        target = String.valueOf(atNode.values.get(j + 1));
                    }
                }
            }
        }
        assertEquals(expectedTarget, target, "@Redirect 的 @At(target) 必须匹配调用点");
    }

    private static MethodNode findMethod(ClassNode node, String name) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name)) {
                return method;
            }
        }
        return null;
    }

    private static AnnotationNode findAnnotation(MethodNode handler, String annotationDesc) {
        if (handler.visibleAnnotations != null) {
            for (AnnotationNode annotation : handler.visibleAnnotations) {
                if (annotationDesc.equals(annotation.desc)) {
                    return annotation;
                }
            }
        }
        return null;
    }

    private static ClassNode readClass(final String internalName) throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream is = StatBonusConcurrencyMixinTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + " 类字节码应在测试 classpath 上");
            new ClassReader(is).accept(node, 0);
        }
        return node;
    }
}
