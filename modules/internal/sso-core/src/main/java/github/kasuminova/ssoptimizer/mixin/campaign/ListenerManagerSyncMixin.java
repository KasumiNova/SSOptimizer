package github.kasuminova.ssoptimizer.mixin.campaign;

import com.fs.util.container.repo.ObjectRepository;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.List;

/** 所有读写共用实例监视器，监听器快照拷贝也必须在锁内完成。 */
@Mixin(targets = GameMixinSignatures.ListenerManager.TARGET_CLASS, remap = false)
public abstract class ListenerManagerSyncMixin {
    @Shadow
    private ObjectRepository listeners;
    @Shadow
    private ObjectRepository transientListeners;

    @Overwrite(remap = false)
    public synchronized void addListener(final Object listener) {
        addListener(listener, false);
    }

    @Overwrite(remap = false)
    public synchronized void addListener(final Object listener, final boolean transientListener) {
        if (transientListener) {
            transientListeners.add(listener);
        } else {
            listeners.add(listener);
        }
    }

    @Overwrite(remap = false)
    public synchronized void removeListener(final Object listener) {
        listeners.remove(listener);
        transientListeners.remove(listener);
    }

    @Overwrite(remap = false)
    public synchronized void removeListenerOfClass(final Class<?> listenerClass) {
        for (final Object listener : new ArrayList<>(listeners.getList(listenerClass))) {
            listeners.remove(listener);
        }
        for (final Object listener : new ArrayList<>(transientListeners.getList(listenerClass))) {
            transientListeners.remove(listener);
        }
    }

    @Overwrite(remap = false)
    public synchronized boolean hasListener(final Object listener) {
        return listeners.contains(listener) || transientListeners.contains(listener);
    }

    @Overwrite(remap = false)
    public synchronized boolean hasListenerOfClass(final Class<?> listenerClass) {
        return !listeners.getList(listenerClass).isEmpty()
                || !transientListeners.getList(listenerClass).isEmpty();
    }

    @Overwrite(remap = false)
    @SuppressWarnings("unchecked")
    public synchronized <T> List<T> getListeners(final Class<T> listenerClass) {
        final List<T> result = new ArrayList<>((List<T>) listeners.getList(listenerClass));
        result.addAll((List<T>) transientListeners.getList(listenerClass));
        return result;
    }
}
