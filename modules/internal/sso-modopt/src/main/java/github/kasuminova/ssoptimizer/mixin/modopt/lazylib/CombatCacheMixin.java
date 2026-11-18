package github.kasuminova.ssoptimizer.mixin.modopt.lazylib;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Pseudo
@Mixin(targets = "org.lazywizard.lazylib.combat.CombatCache", remap = false)
public abstract class CombatCacheMixin {
    @Shadow
    @Final
    private static Map<Integer, Set<ShipAPI>> visCache;

    @Shadow
    private static int lastNumShips;

    @Shadow
    private static float lastTime;

    @Shadow
    private static void cacheMapVisible() {
        throw new AssertionError("Mixin shadow method");
    }

    @Overwrite(remap = false)
    static List<ShipAPI> getCachedVisibleEnemies(final int side) {
        // 失效检查、两侧缓存刷新及弱引用集合复制必须共用同一把锁。
        synchronized (visCache) {
            if (!visCache.containsKey(side)) {
                return Collections.emptyList();
            }
            final CombatEngineAPI engine = Global.getCombatEngine();
            final int numShips = engine.getShips().size();
            final float time = engine.getTotalElapsedTime(true);
            if (numShips != lastNumShips || time != lastTime) {
                cacheMapVisible();
                lastNumShips = numShips;
                lastTime = time;
            }
            return new ArrayList<>(visCache.get(side));
        }
    }
}
