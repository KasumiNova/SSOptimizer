package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.bridge.opengl.DisplayListGuard;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code BeamWeapon.render(Layers; Viewport; F)} 内对公有字段
 * {@code GLListManager.buildingList} 两处直接读（named 源码 BeamWeapon.java:371/394）
 * 的转接，动机同 {@link ShipDisplayListGuardMixin}。
 */
@Mixin(targets = GameClassNames.SHIP_BEAM_WEAPON_DOTTED)
public abstract class BeamWeaponDisplayListGuardMixin {
    private static final String RENDER_METHOD =
            "render(Lcom/fs/starfarer/api/combat/CombatEngineLayers;Lcom/fs/starfarer/combat/CombatViewport;F)V";

    @Redirect(method = RENDER_METHOD, remap = false,
            at = @At(value = "FIELD",
                    target = "Lcom/fs/graphics/util/GLListManager;buildingList:Z",
                    opcode = Opcodes.GETSTATIC, ordinal = 0))
    private boolean ssoptimizer$isBuildingListFirst() {
        return DisplayListGuard.isBuildingList();
    }

    @Redirect(method = RENDER_METHOD, remap = false,
            at = @At(value = "FIELD",
                    target = "Lcom/fs/graphics/util/GLListManager;buildingList:Z",
                    opcode = Opcodes.GETSTATIC, ordinal = 1))
    private boolean ssoptimizer$isBuildingListSecond() {
        return DisplayListGuard.isBuildingList();
    }
}
