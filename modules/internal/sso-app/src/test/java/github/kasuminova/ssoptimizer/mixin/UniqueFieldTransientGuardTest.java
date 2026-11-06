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

class UniqueFieldTransientGuardTest {

    private static final Map<String, String> SAVE_SERIALIZED_TARGET_MIXINS = Map.of(
            "github.kasuminova.ssoptimizer.mixin.render.CampaignShipEngineGlowMixin",
            "CampaignShipEngineGlow",
            "github.kasuminova.ssoptimizer.mixin.render.SpriteAtlasMixin",
            "Sprite",
            "github.kasuminova.ssoptimizer.mixin.render.ShipHullSpecWeaponSlotMixin",
            "ShipHullSpec",
            "github.kasuminova.ssoptimizer.mixin.econ.CommodityOnMarketMixin",
            "CommodityOnMarket",
            "github.kasuminova.ssoptimizer.mixin.econ.MarketMixin",
            "Market",
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
