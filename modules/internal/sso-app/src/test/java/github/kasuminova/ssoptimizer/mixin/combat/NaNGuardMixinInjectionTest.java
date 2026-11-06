package github.kasuminova.ssoptimizer.mixin.combat;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaNGuardMixinInjectionTest {

    private static final String SHIP_APPLY_DAMAGE_INNER_DESC =
            "(Lorg/lwjgl/util/vector/Vector2f;Lcom/fs/starfarer/combat/Damage;ZZF"
                    + "Ljava/lang/Object;)Lcom/fs/starfarer/combat/entities/ship/ApplyDamageResult;";
    private static final String FLUX_TRACKER = "com/fs/starfarer/combat/entities/ship/FluxTracker";
    private static final String COLLISION_HANDLER_IMPL = "com/fs/starfarer/combat/CollisionHandlerImpl";

    @Test
    void combatEngineAdvanceObjectsExists() throws IOException {
        assertMethod(GameClassNames.COMBAT_ENGINE, "advanceObjects", "(F)V", 0);
    }

    @Test
    void shipApplyDamageInnerSixArgOverloadExists() throws IOException {
        assertMethod(GameClassNames.SHIP, "applyDamageInner", SHIP_APPLY_DAMAGE_INNER_DESC, 0);
    }

    @Test
    void baseEntitySetHitpointsExists() throws IOException {
        assertMethod("com/fs/starfarer/combat/entities/BaseEntity", "setHitpoints", "(F)V", 0);
    }

    @Test
    void fluxTrackerFiveArgIncreaseFluxExists() throws IOException {
        assertMethod(FLUX_TRACKER, "increaseFlux", "(FZZZZ)Z", 0);
    }

    @Test
    void fluxTrackerDirectWriteEntriesExist() throws IOException {
        assertMethod(FLUX_TRACKER, "setCurrFlux", "(F)V", 0);
        assertMethod(FLUX_TRACKER, "setMinFlux", "(F)V", 0);
        assertMethod(FLUX_TRACKER, "setHardFlux", "(F)V", 0);
    }

    @Test
    void fluxTrackerOwnerShipFieldExists() throws IOException {
        ClassNode node = readClass(FLUX_TRACKER);
        boolean found = false;
        for (FieldNode field : node.fields) {
            if (field.name.equals("ship")
                    && field.desc.equals("Lcom/fs/starfarer/combat/entities/Ship;")) {
                found = true;
                break;
            }
        }
        assertTrue(found, "FluxTracker.ship 字段（owner 身份）必须存在");
    }

    @Test
    void collisionImpulseIsStatic() throws IOException {
        assertMethod(COLLISION_HANDLER_IMPL, "applyCollisionImpulse",
                "(Lcom/fs/starfarer/combat/CollisionEntity;Lcom/fs/starfarer/combat/CollisionEntity;"
                        + "Lorg/lwjgl/util/vector/Vector2f;)F", Opcodes.ACC_STATIC);
    }

    @Test
    void stageTwoShieldAnchorUniqueInApplyDamageInner() throws IOException {
        MethodNode method = findMethod(GameClassNames.SHIP, "applyDamageInner", SHIP_APPLY_DAMAGE_INNER_DESC);
        assertEquals(1, countWindow(method,
                "M" + Opcodes.INVOKEVIRTUAL + ":" + GameClassNames.SHIELD + ".getFluxPerPointOfDamage()F",
                "I" + Opcodes.FMUL,
                "V" + Opcodes.FSTORE + ":13"), "护盾辐能当量锚点序列必须方法内唯一");

        assertEquals(1, countWindow(method,
                "M" + Opcodes.INVOKEVIRTUAL + ":" + GameClassNames.SHIELD
                        + ".shieldHit(Lorg/lwjgl/util/vector/Vector2f;FZF)V"),
                "Shield.shieldHit INVOKE 必须方法内唯一");
    }

    @Test
    void stageTwoArmorAnchorUniqueInApplyDamageInner() throws IOException {
        MethodNode method = findMethod(GameClassNames.SHIP, "applyDamageInner", SHIP_APPLY_DAMAGE_INNER_DESC);
        assertEquals(1, countWindow(method,
                "V" + Opcodes.FLOAD + ":13",
                "I" + Opcodes.FCONST_0,
                "I" + Opcodes.FCMPL,
                "J" + Opcodes.IFGT,
                "V" + Opcodes.FLOAD + ":15",
                "I" + Opcodes.FCONST_0,
                "I" + Opcodes.FCMPL,
                "J" + Opcodes.IFLE), "装甲路径放行守卫锚点序列必须方法内唯一");

        assertEquals(1, countWindow(method,
                "M" + Opcodes.INVOKEVIRTUAL + ":com/fs/starfarer/combat/entities/ship/ArmorGrid.applyDamage"
                        + "(FFLcom/fs/starfarer/combat/Damage;FLorg/lwjgl/util/vector/Vector2f;F"
                        + "Lcom/fs/starfarer/api/combat/DamageType;)"
                        + "Lcom/fs/starfarer/combat/entities/ship/ApplyDamageResult;"),
                "ArmorGrid.applyDamage INVOKE 必须方法内唯一");
    }

    @Test
    void collisionImpulseStoreSlot16Unique() throws IOException {
        MethodNode method = findMethod(COLLISION_HANDLER_IMPL, "applyCollisionImpulse",
                "(Lcom/fs/starfarer/combat/CollisionEntity;Lcom/fs/starfarer/combat/CollisionEntity;"
                        + "Lorg/lwjgl/util/vector/Vector2f;)F");
        assertEquals(1, countWindow(method, "V" + Opcodes.FSTORE + ":16"),
                "FSTORE 16（冲量槽写入）必须方法内唯一");
    }

    private static int countWindow(final MethodNode method, final String... pattern) {
        List<String> window = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            final String encoded = encode(insn);
            if (encoded == null) {
                continue;
            }
            window.add(encoded);
        }
        int count = 0;
        for (int i = pattern.length; i <= window.size(); i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (!window.get(i - pattern.length + j).equals(pattern[j])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                count++;
            }
        }
        return count;
    }

    private static String encode(final AbstractInsnNode insn) {
        if (insn instanceof InsnNode) {
            return "I" + insn.getOpcode();
        }
        if (insn instanceof VarInsnNode) {
            return "V" + insn.getOpcode() + ":" + ((VarInsnNode) insn).var;
        }
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode) insn;
            return "M" + m.getOpcode() + ":" + m.owner + "." + m.name + m.desc;
        }
        if (insn instanceof JumpInsnNode) {
            return "J" + insn.getOpcode();
        }
        return null;
    }

    private static MethodNode findMethod(final String slashClassName, final String name,
                                         final String desc) throws IOException {
        ClassNode node = readClass(slashClassName);
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        throw new AssertionError(slashClassName + "." + name + desc + " 必须存在（NaN 哨兵注入点）");
    }

    private static void assertMethod(final String slashClassName, final String name, final String desc,
                                     final int requiredAccess) throws IOException {
        MethodNode found = findMethod(slashClassName, name, desc);
        assertNotNull(found, slashClassName + "." + name + desc + " 必须存在（NaN 哨兵注入点）");
        if (requiredAccess != 0) {
            assertTrue((found.access & requiredAccess) != 0,
                    slashClassName + "." + name + " 缺少必需的访问修饰: " + requiredAccess);
        }
    }

    private static ClassNode readClass(final String slashClassName) throws IOException {
        String resource = slashClassName + ".class";
        try (InputStream in = NaNGuardMixinInjectionTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "测试 classpath 必须包含游戏类: " + resource);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
}
