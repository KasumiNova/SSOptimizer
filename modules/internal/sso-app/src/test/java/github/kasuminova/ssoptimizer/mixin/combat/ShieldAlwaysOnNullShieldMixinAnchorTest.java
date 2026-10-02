package github.kasuminova.ssoptimizer.mixin.combat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link ShieldAlwaysOnNullShieldMixin} 注入锚点核验。
 * <p>
 * 重定向正确性取决于「{@code ShieldAlwaysOn.advanceInCombat(Lcom/fs/starfarer/api/combat/ShipAPI;F)V}
 * 内 {@code ShieldAPI.isOn()Z} 调用点恰好唯一」（Mixin 声明 require=1）；
 * 用 ASM 解析测试 classpath 上 named jar 的真实字节码核验，
 * 目标缺失/签名漂移/锚点数量变化都会在构建期暴露而非运行时静默失败。
 */
class ShieldAlwaysOnNullShieldMixinAnchorTest {

    private static final String TARGET_CLASS  = "com/fs/starfarer/api/impl/hullmods/ShieldAlwaysOn";
    private static final String TARGET_METHOD = "advanceInCombat";
    private static final String TARGET_DESC   = "(Lcom/fs/starfarer/api/combat/ShipAPI;F)V";
    private static final String ANCHOR_OWNER  = "com/fs/starfarer/api/combat/ShieldAPI";
    private static final String ANCHOR_NAME   = "isOn";
    private static final String ANCHOR_DESC   = "()Z";

    @Test
    void advanceInCombatExistsWithExactlyOneIsOnCallSite() throws IOException {
        final ClassNode node = readClasspathClass(TARGET_CLASS);

        MethodNode target = null;
        for (final MethodNode method : node.methods) {
            if (TARGET_METHOD.equals(method.name) && TARGET_DESC.equals(method.desc)) {
                target = method;
                break;
            }
        }
        assertNotNull(target, "ShieldAlwaysOn.advanceInCombat" + TARGET_DESC + " 必须存在（重定向目标方法）");

        int isOnCallSites = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode) {
                final MethodInsnNode call = (MethodInsnNode) insn;
                if (ANCHOR_OWNER.equals(call.owner) && ANCHOR_NAME.equals(call.name)
                        && ANCHOR_DESC.equals(call.desc)) {
                    isOnCallSites++;
                }
            }
        }
        assertEquals(1, isOnCallSites,
                "advanceInCombat 内 ShieldAPI.isOn() 调用点必须恰好唯一（require=1 锚点）");
    }

    private static ClassNode readClasspathClass(final String internalName) throws IOException {
        final String resource = internalName + ".class";
        try (InputStream in = ShieldAlwaysOnNullShieldMixinAnchorTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "测试 classpath 必须包含 " + resource);
            final ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
}
