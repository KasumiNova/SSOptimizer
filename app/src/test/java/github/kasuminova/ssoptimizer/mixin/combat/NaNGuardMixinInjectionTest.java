package github.kasuminova.ssoptimizer.mixin.combat;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NaN 哨兵五个 Mixin 的注入目标存在性核验。
 * <p>
 * 游戏类无法脱离引擎实例化，注入是否生效取决于目标方法签名与游戏字节码一致；
 * 用 ASM 解析测试 classpath 上的真实游戏字节码逐一核验（非源码文本匹配），
 * 任一目标缺失/签名漂移都会在构建期暴露而非运行时静默失败。
 */
class NaNGuardMixinInjectionTest {

    @Test
    void combatEngineAdvanceObjectsExists() throws IOException {
        assertMethod(GameClassNames.COMBAT_ENGINE, "advanceObjects", "(F)V", 0);
    }

    @Test
    void shipApplyDamageInnerSixArgOverloadExists() throws IOException {
        // 6 参重载是伤害结算的唯一收口（5 参委托至此），守卫必须钉在这一版上
        assertMethod(GameClassNames.SHIP, "applyDamageInner",
                "(Lorg/lwjgl/util/vector/Vector2f;Lcom/fs/starfarer/combat/Damage;ZZF"
                        + "Ljava/lang/Object;)Lcom/fs/starfarer/combat/entities/ship/ApplyDamageResult;", 0);
    }

    @Test
    void baseEntitySetHitpointsExists() throws IOException {
        assertMethod("com/fs/starfarer/combat/entities/BaseEntity", "setHitpoints", "(F)V", 0);
    }

    @Test
    void fluxTrackerFiveArgIncreaseFluxExists() throws IOException {
        assertMethod("com/fs/starfarer/combat/entities/ship/FluxTracker", "increaseFlux", "(FZZZZ)Z", 0);
    }

    @Test
    void collisionImpulseIsStatic() throws IOException {
        // 静态目标的处理器必须为 static，签名漂移会导致 Mixin 运行时拒绝
        assertMethod("com/fs/starfarer/combat/CollisionHandlerImpl", "applyCollisionImpulse",
                "(Lcom/fs/starfarer/combat/CollisionEntity;Lcom/fs/starfarer/combat/CollisionEntity;"
                        + "Lorg/lwjgl/util/vector/Vector2f;)F", Opcodes.ACC_STATIC);
    }

    private static void assertMethod(final String slashClassName, final String name, final String desc,
                                     final int requiredAccess) throws IOException {
        ClassNode node = readClass(slashClassName);
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                found = method;
                break;
            }
        }
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
