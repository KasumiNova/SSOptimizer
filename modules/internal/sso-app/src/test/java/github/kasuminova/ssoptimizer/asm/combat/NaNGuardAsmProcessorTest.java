package github.kasuminova.ssoptimizer.asm.combat;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ShipDamageStageTwoProcessor} 与 {@link CollisionImpulseClampProcessor}
 * 对真实游戏字节码的织入验证。
 * <p>
 * 直接以测试 classpath 上的 named 游戏类字节驱动处理器，核验织入产物的结构
 * （守卫调用次数/位置、返回指令数变化），并核验非目标类与锚点失配时的放弃语义。
 */
class NaNGuardAsmProcessorTest {

    private static final String GUARD_OWNER = "github/kasuminova/ssoptimizer/common/combat/CombatNaNGuard";

    @Test
    void shipStageTwoWeavesBothGuards() throws IOException {
        byte[] woven = new ShipDamageStageTwoProcessor().process(readClassBytes(GameClassNames.SHIP));
        assertNotNull(woven, "真实 Ship 字节码必须命中两个锚点并完成织入");

        MethodNode method = findMethod(woven, "applyDamageInner",
                ShipDamageStageTwoProcessor.TARGET_DESC);
        assertEquals(1, countInvokeStatic(method, "shouldDiscardShieldDamage"),
                "护盾路径守卫必须恰好织入一次");
        assertEquals(1, countInvokeStatic(method, "shouldDiscardArmorDamage"),
                "装甲路径守卫必须恰好织入一次");
        // 原版 4 处 Profiler.end（三个出口 + catch-any 兜底）+ 两个守卫取消路径各 1 处
        assertEquals(6, countInvokeStaticOwner(method, "com/fs/profiler/Profiler", "end"),
                "守卫取消路径必须配对 Profiler.end（方法头有 Profiler.begin）");
        // 原版 3 处 areturn + 两个守卫取消路径各 1 处
        assertEquals(5, countOpcode(method, Opcodes.ARETURN),
                "守卫取消路径必须直接返回空 ApplyDamageResult");
    }

    @Test
    void shipStageTwoIgnoresNonTargetClass() throws IOException {
        assertNull(new ShipDamageStageTwoProcessor()
                .process(readClassBytes("com/fs/starfarer/combat/entities/ship/FluxTracker")));
    }

    @Test
    void collisionImpulseClampWeavesBeforeVelocityWrites() throws IOException {
        byte[] woven = new CollisionImpulseClampProcessor()
                .process(readClassBytes("com/fs/starfarer/combat/CollisionHandlerImpl"));
        assertNotNull(woven, "真实 CollisionHandlerImpl 字节码必须命中钳制锚点");

        MethodNode method = findMethod(woven, "applyCollisionImpulse",
                CollisionImpulseClampProcessor.TARGET_DESC);
        assertEquals(1, countInvokeStatic(method, "shouldClampImpulse"),
                "冲量钳制守卫必须恰好织入一次");
        // 原版 4 处 freturn + 钳制降级路径 1 处
        assertEquals(5, countOpcode(method, Opcodes.FRETURN),
                "钳制降级必须新增一条 return 0F 路径");
    }

    @Test
    void collisionImpulseClampIgnoresNonTargetClass() throws IOException {
        assertNull(new CollisionImpulseClampProcessor().process(readClassBytes(GameClassNames.SHIP)));
    }

    private static int countInvokeStatic(final MethodNode method, final String name) {
        return countInvokeStaticOwner(method, GUARD_OWNER, name);
    }

    private static int countInvokeStaticOwner(final MethodNode method, final String owner, final String name) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode && insn.getOpcode() == Opcodes.INVOKESTATIC) {
                MethodInsnNode m = (MethodInsnNode) insn;
                if (m.owner.equals(owner) && m.name.equals(name)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int countOpcode(final MethodNode method, final int opcode) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == opcode) {
                count++;
            }
        }
        return count;
    }

    private static MethodNode findMethod(final byte[] classBytes, final String name, final String desc) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        throw new AssertionError("织入产物缺失目标方法: " + name + desc);
    }

    private static byte[] readClassBytes(final String slashClassName) throws IOException {
        String resource = slashClassName + ".class";
        try (InputStream in = NaNGuardAsmProcessorTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "测试 classpath 必须包含游戏类: " + resource);
            return in.readAllBytes();
        }
    }
}
