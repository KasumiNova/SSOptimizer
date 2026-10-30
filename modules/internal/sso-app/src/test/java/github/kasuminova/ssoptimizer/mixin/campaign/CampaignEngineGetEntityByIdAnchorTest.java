package github.kasuminova.ssoptimizer.mixin.campaign;

import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code CampaignEngineGetEntityByIdMixin} 的字节码锚点核验。
 * <p>
 * 对测试 classpath 上 named jar 的真实 {@code CampaignEngine} 逐一核验
 * Mixin 依赖的方法/字段签名：{@code getEntityById}、{@code rebuildIDToEntityMap}、
 * {@code objectAdded}/{@code objectRemoved} 与字段 {@code idToEntity}。
 * 签名在游戏版本更新后漂移时此处直接失败，避免静默注入失效。
 */
class CampaignEngineGetEntityByIdAnchorTest {

    @Test
    void targetSignaturesExist() throws IOException {
        final ClassNode node = readClasspathClass("com/fs/starfarer/campaign/CampaignEngine");

        assertNotNull(findMethod(node, "getEntityById",
                        "(Ljava/lang/String;)Lcom/fs/starfarer/api/campaign/SectorEntityToken;"),
                "缺少 getEntityById(String) 锚点: " + GameMixinSignatures.CampaignEngine.GET_ENTITY_BY_ID);
        assertNotNull(findMethod(node, "rebuildIDToEntityMap", "()V"),
                "缺少 rebuildIDToEntityMap() 锚点");
        assertNotNull(findMethod(node, "objectAdded", "(Ljava/lang/Object;)V"),
                "缺少 objectAdded(Object) 锚点");
        assertNotNull(findMethod(node, "objectRemoved", "(Ljava/lang/Object;)V"),
                "缺少 objectRemoved(Object) 锚点");
        assertNotNull(findMethod(node, "readdChangeListeners", "()V"),
                "缺少 readdChangeListeners() 锚点");

        FieldNode idToEntity = null;
        for (final FieldNode field : node.fields) {
            if (field.name.equals("idToEntity") && field.desc.equals("Ljava/util/Map;")) {
                idToEntity = field;
            }
        }
        assertNotNull(idToEntity, "缺少 idToEntity 字段锚点");
    }

    private static ClassNode readClasspathClass(final String slashName) throws IOException {
        try (InputStream in = CampaignEngineGetEntityByIdAnchorTest.class.getClassLoader()
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
