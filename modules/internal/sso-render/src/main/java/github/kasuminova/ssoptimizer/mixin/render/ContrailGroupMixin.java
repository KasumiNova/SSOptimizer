package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.common.render.engine.ContrailSegmentStore;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * ContrailGroup 段容器数组化：把原版 {@code segments = new LinkedList()} 替换为
 * 数组后备的 {@link ContrailSegmentStore}（v49 profile：LinkedList 迭代 2,473
 * 样本，advance 46.3% + encodeGroup 11% 都迭代同一份段列表）。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.entities.ContrailEngine$ContrailGroup}<br>
 * 注入动机：链表节点逐帧分配 + 节点指针逐段寻址（缓存行不连续）是迭代热点来源；
 * 数组后备把 advance 与 encode 两侧的迭代折叠为连续内存顺序访问。<br>
 * 注入方式：构造器 RETURN 处把 {@code segments} 实例替换为 {@link ContrailSegmentStore}
 * （字段类型仍为 {@code List}，原版对 segments 只用 List API，游戏方法无需改写
 * 即可在数组容器上工作——语义等价论证见 {@link ContrailSegmentStore} 的 javadoc）。<br>
 * 并发边界：advance 与 render 均在游戏主线程调用，容器不做同步。
 */
@Mixin(targets = GameClassNames.CONTRAIL_GROUP_DOTTED)
public abstract class ContrailGroupMixin {

    @SuppressWarnings("rawtypes")
    @Shadow(remap = false)
    private List segments;

    /**
     * 构造器尾部把 LinkedList 实例替换为数组后备容器。原 LinkedList 对象不再被
     * 任何路径引用（advance 已改写为数组迭代，addSegment/removeExpiredSegment
     * 直接操作本容器），无残留状态。
     *
     * @param ci mixin 回调信息（未使用）
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void ssoptimizer$replaceLinkedListWithArrayStore(CallbackInfo ci) {
        this.segments = new ContrailSegmentStore();
    }
}
