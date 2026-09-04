package github.kasuminova.ssoptimizer.mixin.campaign;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code BaseLocationCombatPairingMixin} 的字节码锚点核验。
 * <p>
 * 全方法覆写的正确性前提是「覆写目标签名存在、@Shadow 成员在目标类中全部存在、
 * 被优化的『Checking combat initiation』段结构未被游戏版本改动」。
 * 此处对测试 classpath 上 named jar 的真实 {@code BaseLocation} 逐一核验，
 * 任何锚点漂移（游戏更新）都会在构建期失败，而不是运行期行为劣化。
 */
class BaseLocationCombatPairingAnchorTest {
    private static final String TARGET = "com/fs/starfarer/campaign/BaseLocation";
    private static final String ADVANCE_DESC = "(FLcom/fs/starfarer/util/InputEventList;)V";

    @Test
    void advanceMethodExistsAndCombatSectionStructureUnchanged() throws IOException {
        final ClassNode target = readClasspathClass(TARGET);
        final MethodNode advance = findMethod(target, "advance", ADVANCE_DESC);
        assertNotNull(advance, "BaseLocation.advance" + ADVANCE_DESC + " 必须存在");

        boolean hasCombatSection = false;
        int getListCalls = 0;
        for (final AbstractInsnNode insn : advance.instructions) {
            if (insn instanceof LdcInsnNode
                    && "Checking combat initiation".equals(((LdcInsnNode) insn).cst)) {
                hasCombatSection = true;
            }
            if (insn instanceof MethodInsnNode) {
                final MethodInsnNode call = (MethodInsnNode) insn;
                if (call.owner.equals("com/fs/util/container/repo/ObjectRepository")
                        && call.name.equals("getList")) {
                    getListCalls++;
                }
            }
        }
        assertTrue(hasCombatSection, "advance 内必须存在 \"Checking combat initiation\" Profiler 段");
        assertEquals(5, getListCalls,
                "advance 内 getList 调用数（Entity/LocationToken/Fleet/Station/BaseCampaignEntity）"
                        + "必须为 5，变化说明方法结构已漂移，覆写体需重新对照");
    }

    @Test
    void shadowedMembersExistInTarget() throws IOException {
        final ClassNode target = readClasspathClass(TARGET);
        final String[] fields = {
                "objects", "background", "hitParticles", "lightColor", "lightHeight",
                "lastPlayerVisitTimestamp", "spawnPoints", "idToEntity", "memory",
        };
        for (final String field : fields) {
            assertNotNull(findField(target, field), "BaseLocation 必须存在字段: " + field);
        }
        assertNotNull(findMethod(target, "executeAdds", "()V"));
        assertNotNull(findMethod(target, "executeRemoves", "()V"));
        assertNotNull(findMethod(target, "isCurrentLocation", "()Z"));
        assertNotNull(findMethod(target, "removeObject", "(Ljava/lang/Object;)V"));
    }

    @Test
    void mixinOverwritesAdvanceWithMatchingSignature() throws IOException {
        final ClassNode mixin = readClasspathClass(
                "github/kasuminova/ssoptimizer/mixin/campaign/BaseLocationCombatPairingMixin");
        final MethodNode overwrite = findMethod(mixin, "advance", ADVANCE_DESC);
        assertNotNull(overwrite, "Mixin 必须覆写 advance" + ADVANCE_DESC);
        assertTrue(hasAnnotation(overwrite, "Lorg/spongepowered/asm/mixin/Overwrite;"),
                "advance 覆写必须带 @Overwrite");
    }

    private static boolean hasAnnotation(final MethodNode method, final String desc) {
        if (method.visibleAnnotations == null) {
            return false;
        }
        return method.visibleAnnotations.stream().anyMatch(a -> a.desc.equals(desc));
    }

    private static ClassNode readClasspathClass(final String slashName) throws IOException {
        try (InputStream in = BaseLocationCombatPairingAnchorTest.class.getClassLoader()
                .getResourceAsStream(slashName + ".class")) {
            assertNotNull(in, "测试 classpath 必须包含类: " + slashName);
            final ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static MethodNode findMethod(final ClassNode node, final String name, final String desc) {
        for (final MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        return null;
    }

    private static FieldNode findField(final ClassNode node, final String name) {
        for (final FieldNode field : node.fields) {
            if (field.name.equals(name)) {
                return field;
            }
        }
        return null;
    }
}
