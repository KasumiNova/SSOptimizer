package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.render.atlas.ShipWeaponAtlas;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * 舰船/武器图集构建触发 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.loading.ResourceLoaderState#init}<br>
 * 注入动机：图集构建需要全部 Spec 加载完成（枚举舰船/武器贴图）且主线程持有
 * GL 上下文（上传图集纹理）；init 返回点正好同时满足两者（加载循环内含进度条
 * GL 渲染，返回后即进入标题界面）。<br>
 * 注入效果：init 全部正常返回路径上触发 {@link ShipWeaponAtlas#build()}（幂等）。
 */
@Mixin(targets = GameClassNames.RESOURCE_LOADER_STATE_DOTTED)
public abstract class ShipWeaponAtlasMixin {

    /**
     * @author KasumiNova
     * @reason 加载完成、进入标题前构建舰船/武器贴图图集。
     */
    @Inject(method = "init", at = @At("RETURN"), remap = false)
    private void ssoptimizer$buildShipWeaponAtlas(final Map map, final CallbackInfo ci) {
        ShipWeaponAtlas.build();
    }
}
