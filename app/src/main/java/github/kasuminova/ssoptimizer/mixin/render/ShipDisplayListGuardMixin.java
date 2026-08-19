package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.bridge.opengl.DisplayListGuard;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code Ship.render(Layers; Viewport; Z)} 内对公有字段
 * {@code GLListManager.buildingList} 两处直接读（jitter 渲染路径切换，
 * named 源码 Ship.java:3640/3685）的转接：字段语义已迁入
 * {@link DisplayListGuard} 的 ThreadLocal（「是否处于列表编译中」是线程语义，
 * 全局标志在并行录制下会让并发段落进错分支），此处重定向到
 * {@link DisplayListGuard#isBuildingList()}。
 */
@Mixin(targets = GameClassNames.SHIP_DOTTED)
public abstract class ShipDisplayListGuardMixin {
    private static final String BUILDING_LIST_FIELD =
            "Lcom/fs/graphics/util/GLListManager;buildingList:Z";
    private static final String RENDER_METHOD =
            "render(Lcom/fs/starfarer/api/combat/CombatEngineLayers;Lcom/fs/starfarer/combat/CombatViewport;Z)V";

    @Redirect(method = RENDER_METHOD, remap = false,
            at = @At(value = "FIELD", target = BUILDING_LIST_FIELD,
                    opcode = Opcodes.GETSTATIC, ordinal = 0))
    private boolean ssoptimizer$isBuildingListFirst() {
        return DisplayListGuard.isBuildingList();
    }

    @Redirect(method = RENDER_METHOD, remap = false,
            at = @At(value = "FIELD", target = BUILDING_LIST_FIELD,
                    opcode = Opcodes.GETSTATIC, ordinal = 1))
    private boolean ssoptimizer$isBuildingListSecond() {
        return DisplayListGuard.isBuildingList();
    }
}
