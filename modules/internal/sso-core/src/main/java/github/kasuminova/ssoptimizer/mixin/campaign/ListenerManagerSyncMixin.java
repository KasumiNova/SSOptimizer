package github.kasuminova.ssoptimizer.mixin.campaign;

import com.fs.util.container.repo.ObjectRepository;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.List;

/**
 * 战役监听器管理器线程安全化 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.campaign.ListenerManager} 全部 7 个 API 方法。<br>
 * 注入动机：市场并行推进（{@code -Dssoptimizer.econ.advance.parallel=true}）期间，
 * 模组条件/产业 advance 中的监听器注册会在工作线程上执行
 * {@code addListener}/{@code removeListener}，与主线程
 * {@code ListenerUtil.reportEconomyTick} 的 {@code getListeners} 快照并发。
 * 底层 {@link ObjectRepository} 全部基于非线程安全的 HashMap/ArrayList/HashSet
 * （含 {@code getList} 懒建列表），并发 {@code add} 会在类型列表中产生 null 空洞，
 * 主线程表现即为 {@code ListenerUtil.java:122} 对 null 监听器解引用的 NPE。<br>
 * 注入效果：全部 API 方法以 {@code synchronized}（实例监视器）互斥，读写同锁，
 * {@code getListeners} 的快照拷贝在锁内完成，注册/注销/查询两两原子。
 * 两个 repository 是 {@code ListenerManager} 私有字段、无外部直接访问入口，
 * 方法级互斥即覆盖全部并发路径；原版方法体逻辑逐行保持，仅追加互斥。<br>
 * 覆写方法与原版 API 的对应完备性由 sso-app 的
 * {@code ListenerManagerSyncAnchorTest} 核验。
 */
@Mixin(targets = GameMixinSignatures.ListenerManager.TARGET_CLASS, remap = false)
public abstract class ListenerManagerSyncMixin {
    @Shadow
    private ObjectRepository listeners;
    @Shadow
    private ObjectRepository transientListeners;

    /**
     * @reason 原版逻辑保持，追加 synchronized 互斥（动机见类注释）。
     */
    @Overwrite(remap = false)
    public synchronized void addListener(final Object listener) {
        addListener(listener, false);
    }

    /**
     * @reason 原版逻辑保持，追加 synchronized 互斥（动机见类注释）。
     */
    @Overwrite(remap = false)
    public synchronized void addListener(final Object listener, final boolean transientListener) {
        if (transientListener) {
            transientListeners.add(listener);
        } else {
            listeners.add(listener);
        }
    }

    /**
     * @reason 原版逻辑保持，追加 synchronized 互斥（动机见类注释）。
     */
    @Overwrite(remap = false)
    public synchronized void removeListener(final Object listener) {
        listeners.remove(listener);
        transientListeners.remove(listener);
    }

    /**
     * @reason 原版逻辑保持，追加 synchronized 互斥（动机见类注释）。
     */
    @Overwrite(remap = false)
    public synchronized void removeListenerOfClass(final Class<?> listenerClass) {
        for (final Object listener : new ArrayList<>(listeners.getList(listenerClass))) {
            listeners.remove(listener);
        }
        for (final Object listener : new ArrayList<>(transientListeners.getList(listenerClass))) {
            transientListeners.remove(listener);
        }
    }

    /**
     * @reason 原版逻辑保持，追加 synchronized 互斥（动机见类注释）。
     */
    @Overwrite(remap = false)
    public synchronized boolean hasListener(final Object listener) {
        return listeners.contains(listener) || transientListeners.contains(listener);
    }

    /**
     * @reason 原版逻辑保持，追加 synchronized 互斥（动机见类注释）。
     */
    @Overwrite(remap = false)
    public synchronized boolean hasListenerOfClass(final Class<?> listenerClass) {
        return !listeners.getList(listenerClass).isEmpty()
                || !transientListeners.getList(listenerClass).isEmpty();
    }

    /**
     * @reason 原版逻辑保持，快照拷贝收进锁内（动机见类注释）。
     */
    @Overwrite(remap = false)
    @SuppressWarnings("unchecked")
    public synchronized <T> List<T> getListeners(final Class<T> listenerClass) {
        final List<T> result = new ArrayList<>((List<T>) listeners.getList(listenerClass));
        result.addAll((List<T>) transientListeners.getList(listenerClass));
        return result;
    }
}
