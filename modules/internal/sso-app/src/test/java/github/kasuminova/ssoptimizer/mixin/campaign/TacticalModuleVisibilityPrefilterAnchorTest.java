package github.kasuminova.ssoptimizer.mixin.campaign;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code TacticalModuleVisibilityPrefilterMixin} 的字节码锚点核验。
 * <p>
 * 预过滤 @Redirect 的正确性前提是「{@code TacticalModule.advance(float)} 内
 * {@code ObjectRepository.getList(Class)} 调用点唯一」（另一处同名调用必须在
 * {@code hasEnoughStuffAround} 内而非 advance），以及 @Shadow 的 {@code fleet} 字段存在。
 */
class TacticalModuleVisibilityPrefilterAnchorTest {
    private static final String TARGET = "com/fs/starfarer/campaign/ai/TacticalModule";
    private static final String GET_LIST_OWNER = "com/fs/util/container/repo/ObjectRepository";

    @Test
    void advanceHasExactlyOneGetListCallSite() throws IOException {
        final ClassNode target = readClasspathClass(TARGET);
        final MethodNode advance = findMethod(target, "advance", "(F)V");
        assertNotNull(advance, "TacticalModule.advance(F)V 必须存在");

        int getListCalls = 0;
        for (final AbstractInsnNode insn : advance.instructions) {
            if (insn instanceof MethodInsnNode) {
                final MethodInsnNode call = (MethodInsnNode) insn;
                if (call.owner.equals(GET_LIST_OWNER) && call.name.equals("getList")
                        && call.desc.equals("(Ljava/lang/Class;)Ljava/util/List;")) {
                    getListCalls++;
                }
            }
        }
        assertEquals(1, getListCalls,
                "advance 内 ObjectRepository.getList(Class) 调用点必须唯一，"
                        + "否则 @Redirect 锚点歧义需重新核对");
    }

    @Test
    void shadowedFleetFieldExists() throws IOException {
        final ClassNode target = readClasspathClass(TARGET);
        FieldNode fleet = null;
        for (final FieldNode field : target.fields) {
            if (field.name.equals("fleet")) {
                fleet = field;
                break;
            }
        }
        assertNotNull(fleet, "TacticalModule 必须存在 fleet 字段");
        assertEquals("Lcom/fs/starfarer/campaign/fleet/CampaignFleet;", fleet.desc,
                "fleet 字段类型必须为 CampaignFleet");
    }

    @Test
    void mixinRedirectMethodExists() throws IOException {
        final ClassNode mixin = readClasspathClass(
                "github/kasuminova/ssoptimizer/mixin/campaign/TacticalModuleVisibilityPrefilterMixin");
        assertNotNull(findMethod(mixin, "ssoptimizer$prefilterScannedFleets",
                        "(Lcom/fs/util/container/repo/ObjectRepository;Ljava/lang/Class;)Ljava/util/List;"),
                "Mixin 必须存在与 getList 签名匹配的 redirect 方法");
    }

    private static ClassNode readClasspathClass(final String slashName) throws IOException {
        try (InputStream in = TacticalModuleVisibilityPrefilterAnchorTest.class.getClassLoader()
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
}
