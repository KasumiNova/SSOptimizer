package github.kasuminova.ssoptimizer.mixin.save;

import github.kasuminova.ssoptimizer.common.save.MemoryRefResolveGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.LinkedHashMap;

/**
 * Memory 引用解析守卫 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.campaign.rules.Memory}（DoNotObfuscate）<br>
 * 注入动机：读档 unmarshal 期间引擎单例未指向新引擎，模组在 readResolve 链上
 * 触碰舰队 memory 会触发 {@code replaceIdsWithEntities} 的提前解析，
 * 全部 mRef_/enRef_ 反查必然失败并被原版静默删键（详见
 * {@link MemoryRefResolveGuard} 类注释）。<br>
 * 注入效果：重定向方法内两处 {@code LinkedHashMap.remove} 删除调用点，
 * 不安全窗口内挂起删除并记录本实例；新引擎安装后由守卫重放解析。
 * 窗口外行为与原版逐字节等价。
 */
@Mixin(targets = "com.fs.starfarer.campaign.rules.Memory", remap = false)
public abstract class MemoryRefResolveGuardMixin implements MemoryRefResolveGuard.PendingResolution {
    @Shadow
    private LinkedHashMap<String, Object> data;

    @Shadow
    public abstract void replaceIdsWithEntities(LinkedHashMap<String, Object> data);

    /**
     * 删除守卫：不安全窗口内挂起删除并记录，窗口外直通原版语义。
     *
     * @param map 目标数据表
     * @param key 待删键
     * @return 原版 remove 返回值；挂起时返回 null
     * @author KasumiNova
     * @reason 窗口内的删除必然基于错误的引擎状态，属于确定性数据损坏，必须挂起。
     */
    @Redirect(method = "replaceIdsWithEntities(Ljava/util/LinkedHashMap;)V",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/LinkedHashMap;remove(Ljava/lang/Object;)Ljava/lang/Object;"),
            expect = 2, remap = false)
    private Object ssoptimizer$guardRemoval(final LinkedHashMap map, final Object key) {
        if (MemoryRefResolveGuard.isUnsafeWindow()) {
            MemoryRefResolveGuard.recordSuppressed(this);
            return null;
        }
        return map.remove(key);
    }

    /**
     * 重放解析：新引擎安装后以完整经济体/实体状态重新执行引用反查。
     */
    @Unique
    @Override
    public void ssoptimizer$rerunResolution() {
        replaceIdsWithEntities(data);
    }
}
