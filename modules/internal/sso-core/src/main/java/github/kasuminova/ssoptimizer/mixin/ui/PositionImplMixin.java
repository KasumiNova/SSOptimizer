package github.kasuminova.ssoptimizer.mixin.ui;

import com.fs.starfarer.ui.PositionImpl;
import github.kasuminova.ssoptimizer.common.ui.PositionSortHelper;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/**
 * UI 兄弟锚点排序 Mixin（{@code PositionImpl.sortChildren} O(轮数×n²) 热点消除）。
 * <p>
 * 注入目标：{@code com.fs.starfarer.ui.PositionImpl#sortChildren()}<br>
 * 注入动机：原版排序是「按原顺序多轮扫描、锚点已就位即追加」的拓扑排序，
 * 成员判定走 {@code LinkedList.contains} 线性扫描。重构装配界面
 * （{@code RefitTab.rebuildMemberList → ScrollingList.addItem}）每个条目都会
 * 触发一次 {@code recompute → sortChildren}，组件数百级时打开一次对话框
 * 可达秒级阻塞（JProfiler 实测 {@code LinkedList.contains} 占该路径 48%+）。<br>
 * 为什么 @Overwrite 而不是锚点注入：目标方法是私有零参方法，整个方法体
 * 就是排序循环本身，不存在「保留部分逻辑」的注入点；覆写体仅保留原版守卫
 * 条件，排序委托给可单测的 {@link PositionSortHelper}。<br>
 * 等价性：守卫条件（{@code suspendRecompute}/{@code withSort}/{@code suspendSort}）
 * 逐字对应原版嵌套 if；排序语义（稳定拓扑序、两个异常文案与抛出条件、
 * 恒等成员判定）见 {@link PositionSortHelper} 类注释。
 */
@Mixin(targets = GameMixinSignatures.PositionImpl.TARGET_CLASS, remap = false)
public abstract class PositionImplMixin {
    @Shadow
    private List<PositionImpl> children;
    @Shadow
    private boolean suspendRecompute;
    @Shadow
    private boolean withSort;
    @Shadow
    private static boolean suspendSort;

    /**
     * @reason 排序循环整体替换为集合成员判定版本（动机与等价性见类注释）；
     *         守卫条件与原版逐字等价。
     */
    @Overwrite(remap = false)
    private void sortChildren() {
        if (!this.suspendRecompute) {
            if (this.withSort && !suspendSort) {
                PositionSortHelper.sortByAnchor(this.children, PositionImpl::getBase);
            }
        }
    }
}
