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
 * {@link AiUtilsEscortTargetGuardMixin} 的注入配置正确性验证。
 * <p>
 * 游戏类 AIUtils 无法脱离引擎实例化，故不直接调用织入后的方法；改为用 ASM
 * 解析测试 classpath 上的真实字节码做两级校验（非源码文本匹配）：
 * <ol>
 *   <li>游戏侧：私有递归版 {@code isEscortTargetOf(Ship,Ship,int)} 必须以预期
 *       签名存在且为 private static（否则注入静默失败）；公开 2 参重载的存在
 *       同时确认注入描述符起到了区分重载的作用；</li>
 *   <li>Mixin 侧：处理器必须为 private static（Mixin 运行时校验拒绝非 private
 *       静态处理器，实机已踩坑），其 {@code @Inject} 注解的 method/cancellable/
 *       remap 必须与目标签名一致。</li>
 * </ol>
 */
class AiUtilsEscortTargetGuardMixinTest {
    private static final String PRIVATE_DESC =
            "(Lcom/fs/starfarer/combat/entities/Ship;Lcom/fs/starfarer/combat/entities/Ship;I)Z";
    private static final String PUBLIC_DESC =
            "(Lcom/fs/starfarer/combat/entities/Ship;Lcom/fs/starfarer/combat/entities/Ship;)Z";
    private static final String HANDLER_NAME = "ssoptimizer$guardNullEscortArgs";
    private static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT_DESC = "Lorg/spongepowered/asm/mixin/injection/At;";

    @Test
    void injectionTargetExistsAsPrivateStaticOverload() throws IOException {
        ClassNode node = readClass(GameClassNames.AI_UTILS);

        MethodNode privateOverload = null;
        MethodNode publicOverload = null;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("isEscortTargetOf")) {
                continue;
            }
            if (method.desc.equals(PRIVATE_DESC)) {
                privateOverload = method;
            } else if (method.desc.equals(PUBLIC_DESC)) {
                publicOverload = method;
            }
        }

        assertNotNull(privateOverload, "私有递归版 isEscortTargetOf(Ship,Ship,int) 必须存在（注入点）");
        assertTrue((privateOverload.access & Opcodes.ACC_PRIVATE) != 0, "注入目标应为私有方法");
        assertTrue((privateOverload.access & Opcodes.ACC_STATIC) != 0, "注入目标应为静态方法");
        assertNotNull(publicOverload, "公开版 isEscortTargetOf(Ship,Ship) 应存在（验证描述符区分重载）");
    }

    @Test
    void handlerIsPrivateStaticWithMatchingInjectConfig() throws IOException {
        ClassNode node = readClass(
                "github/kasuminova/ssoptimizer/mixin/ai/AiUtilsEscortTargetGuardMixin");

        MethodNode handler = null;
        for (MethodNode method : node.methods) {
            if (method.name.contains(HANDLER_NAME)) {
                handler = method;
                break;
            }
        }
        assertNotNull(handler, "Mixin 处理器方法必须存在");
        assertTrue((handler.access & Opcodes.ACC_PRIVATE) != 0
                        && (handler.access & Opcodes.ACC_STATIC) != 0,
                "处理器必须为 private static（Mixin 运行时校验硬性要求，实机已验证非 private 静态会被拒）");

        AnnotationNode inject = null;
        if (handler.visibleAnnotations != null) {
            for (AnnotationNode annotation : handler.visibleAnnotations) {
                if (INJECT_DESC.equals(annotation.desc)) {
                    inject = annotation;
                    break;
                }
            }
        }
        assertNotNull(inject, "处理器必须带 @Inject 注解");

        List<String> injectMethods = new ArrayList<>();
        boolean cancellable = false;
        boolean remap = true;
        String atValue = null;
        for (int i = 0; i + 1 < inject.values.size(); i += 2) {
            Object key = inject.values.get(i);
            Object value = inject.values.get(i + 1);
            if ("method".equals(key) && value instanceof List<?> list) {
                for (Object entry : list) {
                    injectMethods.add(String.valueOf(entry));
                }
            } else if ("cancellable".equals(key)) {
                cancellable = Boolean.TRUE.equals(value);
            } else if ("remap".equals(key)) {
                remap = Boolean.TRUE.equals(value);
            } else if ("at".equals(key) && value instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof AnnotationNode at && at.values != null) {
                // @Inject.at 声明为 At[]，单元素也以单元素列表形式出现
                for (int j = 0; j + 1 < at.values.size(); j += 2) {
                    if ("value".equals(at.values.get(j))) {
                        atValue = String.valueOf(at.values.get(j + 1));
                    }
                }
            }
        }

        assertEquals(List.of("isEscortTargetOf" + PRIVATE_DESC), injectMethods,
                "@Inject.method 必须精确指向私有递归版签名");
        assertTrue(cancellable, "@Inject 必须 cancellable（null 时短路返回）");
        assertTrue(!remap, "@Inject 必须 remap=false（游戏类不走混淆映射）");
        assertEquals("HEAD", atValue, "注入点必须在方法头");
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
