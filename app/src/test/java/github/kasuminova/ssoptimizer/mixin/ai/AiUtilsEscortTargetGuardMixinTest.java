package github.kasuminova.ssoptimizer.mixin.ai;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiUtilsEscortTargetGuardMixin} 注入配置正确性验证。
 * <p>
 * 游戏类 AIUtils 无法脱离引擎实例化，不直接调用织入后的方法；用 ASM 解析
 * 测试 classpath 上的真实字节码做两级校验（非源码文本匹配）：
 * <ol>
 *   <li>游戏侧：私有递归版 {@code isEscortTargetOf(Ship,Ship,int)} 必须存在
 *       且为 private static（否则注入静默失败）；</li>
 *   <li>Mixin 侧：两个处理器必须为 private static（Mixin 运行时校验拒绝非
 *       private 静态处理器），{@code @Inject}（arg 守卫）与
 *       {@code @Redirect}（maneuver 守卫）的 method/remap 与目标一致。</li>
 * </ol>
 */
class AiUtilsEscortTargetGuardMixinTest {
    private static final String PRIVATE_DESC =
            "(Lcom/fs/starfarer/combat/entities/Ship;Lcom/fs/starfarer/combat/entities/Ship;I)Z";
    private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String REDIRECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String AT_DESC = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String ESCORT_TARGET_GETTER =
            "Lcom/fs/starfarer/combat/ai/movement/maneuvers/EscortTargetManeuverV3;getTargetShip()Lcom/fs/starfarer/combat/CollisionEntity;";

    @Test
    void injectionTargetExistsAsPrivateStaticOverload() throws IOException {
        ClassNode node = readClass(GameClassNames.AI_UTILS);

        MethodNode privateOverload = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("isEscortTargetOf") && method.desc.equals(PRIVATE_DESC)) {
                privateOverload = method;
                break;
            }
        }
        assertNotNull(privateOverload, "私有递归版 isEscortTargetOf(Ship,Ship,int) 必须存在（注入点）");
        assertTrue((privateOverload.access & Opcodes.ACC_PRIVATE) != 0, "注入目标应为私有方法");
        assertTrue((privateOverload.access & Opcodes.ACC_STATIC) != 0, "注入目标应为静态方法");
        // 私有版内部必须真实调用 EscortTargetManeuverV3.getTargetShip()（@Redirect 的注入点）
        boolean hasGetTargetShip = false;
        for (org.objectweb.asm.tree.AbstractInsnNode insn : privateOverload.instructions) {
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode methodInsn
                    && methodInsn.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && methodInsn.owner.equals("com/fs/starfarer/combat/ai/movement/maneuvers/EscortTargetManeuverV3")
                    && methodInsn.name.equals("getTargetShip")) {
                hasGetTargetShip = true;
                break;
            }
        }
        assertTrue(hasGetTargetShip, "私有递归版内部必须调用 getTargetShip()（maneuver 守卫的注入点）");
    }

    @Test
    void handlersArePrivateStaticWithMatchingConfig() throws IOException {
        ClassNode node = readClass(
                "github/kasuminova/ssoptimizer/mixin/ai/AiUtilsEscortTargetGuardMixin");

        MethodNode injectHandler = findMethod(node, "ssoptimizer$guardNullEscortArgs");
        assertNotNull(injectHandler, "arg 守卫处理器必须存在");
        assertPrivateStatic(injectHandler);
        assertAnnotationConfig(injectHandler, INJECT_DESC, "isEscortTargetOf" + PRIVATE_DESC, true, "HEAD");

        MethodNode redirectHandler = findMethod(node, "ssoptimizer$guardEscortManeuverTarget");
        assertNotNull(redirectHandler, "maneuver 守卫处理器必须存在");
        assertPrivateStatic(redirectHandler);
        assertRedirectConfig(redirectHandler, "isEscortTargetOf" + PRIVATE_DESC, ESCORT_TARGET_GETTER);
    }

    private static void assertPrivateStatic(MethodNode handler) {
        assertTrue((handler.access & Opcodes.ACC_PRIVATE) != 0
                        && (handler.access & Opcodes.ACC_STATIC) != 0,
                "处理器必须为 private static（Mixin 运行时校验硬性要求）");
    }

    private static void assertAnnotationConfig(MethodNode handler, String annotationDesc,
                                               String expectedMethod, boolean cancellable,
                                               String atValue) {
        AnnotationNode annotation = findAnnotation(handler, annotationDesc);
        assertNotNull(annotation, "处理器必须带目标注解");

        List<String> methods = new ArrayList<>();
        boolean canc = false;
        boolean remap = true;
        String at = null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            Object key = annotation.values.get(i);
            Object value = annotation.values.get(i + 1);
            if ("method".equals(key) && value instanceof List<?> list) {
                for (Object entry : list) {
                    methods.add(String.valueOf(entry));
                }
            } else if ("cancellable".equals(key)) {
                canc = Boolean.TRUE.equals(value);
            } else if ("remap".equals(key)) {
                remap = Boolean.TRUE.equals(value);
            } else if ("at".equals(key) && value instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof AnnotationNode atNode && atNode.values != null) {
                for (int j = 0; j + 1 < atNode.values.size(); j += 2) {
                    if ("value".equals(atNode.values.get(j))) {
                        at = String.valueOf(atNode.values.get(j + 1));
                    }
                }
            }
        }
        assertEquals(List.of(expectedMethod), methods, "@Inject.method 必须精确指向目标签名");
        assertEquals(cancellable, canc, "@Inject 必须 cancellable");
        assertTrue(!remap, "@Inject 必须 remap=false");
        assertEquals(atValue, at, "注入点位置不符");
    }

    private static void assertRedirectConfig(MethodNode handler, String expectedMethod,
                                             String expectedTarget) {
        AnnotationNode redirect = findAnnotation(handler, REDIRECT_DESC);
        assertNotNull(redirect, "maneuver 守卫必须带 @Redirect 注解");

        List<String> methods = new ArrayList<>();
        boolean remap = true;
        String target = null;
        for (int i = 0; i + 1 < redirect.values.size(); i += 2) {
            Object key = redirect.values.get(i);
            Object value = redirect.values.get(i + 1);
            if ("method".equals(key) && value instanceof List<?> list) {
                for (Object entry : list) {
                    methods.add(String.valueOf(entry));
                }
            } else if ("remap".equals(key)) {
                remap = Boolean.TRUE.equals(value);
            } else if ("at".equals(key)) {
                // @Redirect 的 at 为单个 At（非 @Inject 的 At[] 数组），兼容两种形态
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
        }
        assertEquals(List.of(expectedMethod), methods, "@Redirect.method 必须精确指向目标签名");
        assertEquals(expectedTarget, target, "@Redirect 的 @At(target) 必须匹配 getTargetShip 调用");
        assertTrue(!remap, "@Redirect 必须 remap=false");
    }

    private static MethodNode findMethod(ClassNode node, String name) {
        for (MethodNode method : node.methods) {
            if (method.name.contains(name)) {
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
        try (InputStream is = AiUtilsEscortTargetGuardMixinTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + " 类字节码应在测试 classpath 上");
            new ClassReader(is).accept(node, 0);
        }
        return node;
    }
}
