package github.kasuminova.ssoptimizer.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「会被战役存档 XStream 序列化的游戏类」上的 Mixin 注入字段必须 transient 的
 * 结构性守卫。
 * <p>
 * 背景：{@code CampaignShipEngineGlowMixin.ssoptimizer$glowGeometryCache} 未标
 * transient 导致注入字段被写入存档、读档失败（IntArrayConverter 空串解析）。
 * 本测试用 ASM 直接扫描 Mixin 类字节码（不经反射、不经 Mixin 运行时），
 * 断言清单内每个类的全部非 static {@code @Unique} 字段均带 ACC_TRANSIENT。
 * <p>
 * 清单 = 当前判定为「目标类进战役存档」的全部 Mixin；新增同类 Mixin 时应加入清单。
 */
class UniqueFieldTransientGuardTest {

    /**
     * 目标类会被战役存档序列化的 Mixin 清单 → 目标类与判定依据。
     */
    private static final Map<String, String> SAVE_SERIALIZED_TARGET_MIXINS = Map.of(
            // CampaignShipEngineGlow 随 CampaignFleetMemberView 进存档
            "github.kasuminova.ssoptimizer.mixin.render.CampaignShipEngineGlowMixin",
            "CampaignShipEngineGlow",
            // Sprite 在 save XStream 配置中有 alias（CampaignGameManager.getXStream）
            "github.kasuminova.ssoptimizer.mixin.render.SpriteAtlasMixin",
            "Sprite",
            // ShipHullSpec 存在 clone/序列化路径
            "github.kasuminova.ssoptimizer.mixin.render.ShipHullSpecWeaponSlotMixin",
            "ShipHullSpec",
            // CommodityOnMarket 随 Market 进经济体存档
            "github.kasuminova.ssoptimizer.mixin.econ.CommodityOnMarketMixin",
            "CommodityOnMarket",
            // Market 随经济体进存档
            "github.kasuminova.ssoptimizer.mixin.econ.MarketMixin",
            "Market",
            // MutableStat 随角色/舰队统计进存档（原版 needsRecompute 亦标 transient）
            "github.kasuminova.ssoptimizer.mixin.combat.MutableStatMutationMixin",
            "MutableStat");

    @Test
    void uniqueInstanceFieldsOnSaveSerializedTargetsAreTransient() {
        final List<String> violations = new ArrayList<>();
        SAVE_SERIALIZED_TARGET_MIXINS.forEach((mixinClass, target) -> {
            final ClassNode node = readClass(mixinClass);
            for (final FieldNode field : node.fields) {
                if ((field.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0) {
                    continue;
                }
                if (!hasAnnotation(field, "Lorg/spongepowered/asm/mixin/Unique;")) {
                    continue;
                }
                if ((field.access & org.objectweb.asm.Opcodes.ACC_TRANSIENT) == 0) {
                    violations.add(mixinClass + "." + field.name
                            + "（目标 " + target + " 进战役存档，@Unique 字段必须 transient）");
                }
            }
        });
        assertTrue(violations.isEmpty(), () -> "非 transient 的注入字段:\n" + String.join("\n", violations));
    }

    private static boolean hasAnnotation(final FieldNode field, final String desc) {
        return containsDesc(field.visibleAnnotations, desc)
                || containsDesc(field.invisibleAnnotations, desc);
    }

    private static boolean containsDesc(final List<AnnotationNode> annotations, final String desc) {
        if (annotations == null) {
            return false;
        }
        for (final AnnotationNode annotation : annotations) {
            if (desc.equals(annotation.desc)) {
                return true;
            }
        }
        return false;
    }

    private static ClassNode readClass(final String className) {
        final String resource = className.replace('.', '/') + ".class";
        try (InputStream in = UniqueFieldTransientGuardTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "Mixin 类不在测试 classpath: " + className);
            final ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
            return node;
        } catch (final java.io.IOException e) {
            throw new IllegalStateException("无法读取 Mixin 类字节码: " + className, e);
        }
    }
}
