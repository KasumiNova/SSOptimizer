package github.kasuminova.ssoptimizer.mixin.ai;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模组可写 Map 的 null 兼容契约验证（ASM 解析真实字节码，模式同
 * StatBonusConcurrencyMixinTest）。
 * <p>
 * 背景：CombatEngine.customData 与 TimeoutTrackerMap.items 原版分别为 HashMap /
 * LinkedHashMap，允许 null key/value；模组（如 ls_grot_rigger 写入未初始化字段）
 * 依赖该行为。两处并发化 Mixin 必须使用 {@code Collections.synchronizedMap} 包装，
 * 不得回退为 ConcurrentHashMap（null 限制会让模组直接 NPE）。
 */
class ModDataMapNullCompatMixinTest {

    @Test
    void combatEngineInitStillAssignsHashMapToCustomData() throws IOException {
        ClassNode node = readClass(GameClassNames.COMBAT_ENGINE);
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) {
                continue;
            }
            if (assignsFieldFromNew(method, "customData", "java/util/HashMap")) {
                found = true;
                break;
            }
        }
        assertTrue(found, "CombatEngine.<init> 必须存在 customData = new HashMap（注入点）");
    }

    @Test
    void timeoutTrackerMapInitStillCreatesLinkedHashMap() throws IOException {
        ClassNode node = readClass(GameClassNames.TIMEOUT_TRACKER_MAP);
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) {
                continue;
            }
            if (assignsFieldFromNew(method, "items", "java/util/LinkedHashMap")) {
                found = true;
                break;
            }
        }
        assertTrue(found, "TimeoutTrackerMap.<init> 必须存在 items = new LinkedHashMap（注入点）");
    }

    @Test
    void customDataHandlerUsesSynchronizedMapAndNeverChm() throws IOException {
        ClassNode mixin = readClass(
                "github/kasuminova/ssoptimizer/mixin/ai/CombatEngineAiParallelMixin");
        MethodNode handler = findMethod(mixin, "ssoptimizer$concurrentCustomData");
        assertNotNull(handler, "customData 并发化处理器必须存在");
        assertInitReturnInject(handler);
        assertTrue(invokesMethod(handler, "java/util/Collections", "synchronizedMap"),
                "处理器必须调用 Collections.synchronizedMap（null 兼容）");
        assertNoConcurrentHashMapUsage(mixin);
    }

    @Test
    void timeoutTrackerHandlerUsesSynchronizedMapAndNeverChm() throws IOException {
        ClassNode mixin = readClass(
                "github/kasuminova/ssoptimizer/mixin/ai/TimeoutTrackerMapConcurrentMixin");
        MethodNode handler = findMethod(mixin, "ssoptimizer$concurrentItems");
        assertNotNull(handler, "items 并发化处理器必须存在");
        assertInitReturnInject(handler);
        assertTrue(invokesMethod(handler, "java/util/Collections", "synchronizedMap"),
                "处理器必须调用 Collections.synchronizedMap（null 兼容）");
        assertNoConcurrentHashMapUsage(mixin);
    }

    private static void assertNoConcurrentHashMapUsage(ClassNode mixin) {
        for (MethodNode method : mixin.methods) {
            for (var insn : method.instructions) {
                if (insn instanceof TypeInsnNode typeInsn) {
                    assertFalse(typeInsn.desc.contains("ConcurrentHashMap"),
                            mixin.name + "." + method.name + " 不得再使用 ConcurrentHashMap");
                } else if (insn instanceof MethodInsnNode call) {
                    assertFalse(call.owner.contains("ConcurrentHashMap"),
                            mixin.name + "." + method.name + " 不得再调用 ConcurrentHashMap");
                } else if (insn instanceof FieldInsnNode field) {
                    assertFalse(field.owner.contains("ConcurrentHashMap"),
                            mixin.name + "." + method.name + " 不得再引用 ConcurrentHashMap");
                }
            }
        }
    }

    private static void assertInitReturnInject(MethodNode handler) {
        AnnotationNode inject = findAnnotation(handler,
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertNotNull(inject, "处理器必须带 @Inject 注解");
        List<String> methods = new ArrayList<>();
        String atValue = null;
        boolean remap = true;
        for (int i = 0; i + 1 < inject.values.size(); i += 2) {
            Object key = inject.values.get(i);
            Object value = inject.values.get(i + 1);
            if ("method".equals(key) && value instanceof List<?> list) {
                for (Object entry : list) {
                    methods.add(String.valueOf(entry));
                }
            } else if ("remap".equals(key)) {
                remap = Boolean.TRUE.equals(value);
            } else if ("at".equals(key)) {
                AnnotationNode atNode = value instanceof AnnotationNode node ? node
                        : (value instanceof List<?> list && !list.isEmpty()
                                && list.get(0) instanceof AnnotationNode node ? node : null);
                if (atNode != null && atNode.values != null) {
                    for (int j = 0; j + 1 < atNode.values.size(); j += 2) {
                        if ("value".equals(atNode.values.get(j))) {
                            atValue = String.valueOf(atNode.values.get(j + 1));
                        }
                    }
                }
            }
        }
        assertTrue(methods.contains("<init>"), "@Inject.method 必须指向 <init>");
        assertTrue("RETURN".equals(atValue), "@At 必须为 RETURN");
        assertFalse(remap, "@Inject 必须 remap=false");
    }

    private static boolean assignsFieldFromNew(MethodNode method, String fieldName, String newType) {
        boolean sawNew = false;
        for (var insn : method.instructions) {
            if (insn instanceof TypeInsnNode typeInsn
                    && typeInsn.getOpcode() == Opcodes.NEW && newType.equals(typeInsn.desc)) {
                sawNew = true;
            } else if (sawNew && insn instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && fieldName.equals(field.name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean invokesMethod(MethodNode method, String owner, String name) {
        for (var insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && owner.equals(call.owner)
                    && name.equals(call.name)) {
                return true;
            }
        }
        return false;
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
        try (InputStream is = ModDataMapNullCompatMixinTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(is, internalName + " 类字节码应在测试 classpath 上");
            new ClassReader(is).accept(node, 0);
        }
        return node;
    }
}
