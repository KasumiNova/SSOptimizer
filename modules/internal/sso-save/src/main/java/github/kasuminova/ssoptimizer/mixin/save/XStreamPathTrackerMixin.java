package github.kasuminova.ssoptimizer.mixin.save;

import com.thoughtworks.xstream.io.path.Path;
import github.kasuminova.ssoptimizer.common.save.XStreamPathTrackerHelper;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * XStream 路径跟踪器加速 Mixin。
 * <p>
 * 注入目标：{@code com.thoughtworks.xstream.io.path.PathTracker}<br>
 * 注入动机：热点分析显示 `getPath()`/`peekElement()` 会在序列化阶段频繁调用，并且原实现每次都依赖
 * map 查询与即时字符串拼接来构造带兄弟序号的路径片段。<br>
 * 注入效果：在 `pushElement()` 时预先缓存格式化路径片段，后续 `peekElement()` 直接返回缓存，
 * `getPath()` 则通过数组复制一次性构造 {@link Path}。
 */
@Mixin(targets = "com.thoughtworks.xstream.io.path.PathTracker")
public abstract class XStreamPathTrackerMixin {
    @Shadow(remap = false)
    private int pointer;

    @Shadow(remap = false)
    private int capacity;

    @Shadow(remap = false)
    private String[] pathStack;

    @Shadow(remap = false)
    private Map[] indexMapStack;

    @Shadow(remap = false)
    private Path currentPath;

    @Shadow(remap = false)
    private void resizeStacks(final int newCapacity) {
        throw new AssertionError();
    }

    @Unique
    private String[] ssoptimizer$formattedPathStack;

    @Inject(method = "<init>(I)V", at = @At("RETURN"), remap = false)
    private void ssoptimizer$initFormattedPathStack(final int initialCapacity, final CallbackInfo callbackInfo) {
        ssoptimizer$formattedPathStack = XStreamPathTrackerHelper.createFormattedPathStack(capacity);
    }

    /**
     * 推入一个新节点。
     *
     * @param name 节点名
     * @author GitHub Copilot
     * @reason 在入栈阶段预先计算带兄弟序号的显示路径，避免后续 `peekElement/getPath` 重复查询 map 和拼接字符串。
     */
    @Overwrite(remap = false)
    public void pushElement(final String name) {
        if (pointer + 1 >= capacity) {
            resizeStacks(capacity * 2);
            ssoptimizer$formattedPathStack = XStreamPathTrackerHelper.resizeFormattedPathStack(
                    ssoptimizer$formattedPathStack,
                    capacity
            );
        }

        pathStack[pointer] = name;
        Map<String, Integer> indexMap = indexMapStack[pointer];
        if (indexMap == null) {
            indexMap = new Object2ObjectOpenHashMap<>();
            indexMapStack[pointer] = indexMap;
        }

        final Integer currentIndex = indexMap.get(name);
        final int nextIndex = currentIndex != null ? currentIndex + 1 : 1;
        indexMap.put(name, nextIndex);
        ssoptimizer$formattedPathStack[pointer] = XStreamPathTrackerHelper.formatElement(name, nextIndex);

        pointer++;
        currentPath = null;
    }

    /**
     * 弹出当前节点。
     *
     * @author GitHub Copilot
     * @reason 与原实现保持同级计数 map 生命周期一致，同时清理缓存路径对象和更深层级的格式化片段。
     */
    @Overwrite(remap = false)
    public void popElement() {
        indexMapStack[pointer] = null;
        pathStack[pointer] = null;
        ssoptimizer$formattedPathStack[pointer] = null;
        currentPath = null;
        pointer--;
    }

    /**
     * 查看当前节点路径片段。
     *
     * @return 当前路径片段
     * @author GitHub Copilot
     * @reason 复用预计算路径片段，避免无参调用再回落到旧的即时拼接路径。
     */
    @Overwrite(remap = false)
    public String peekElement() {
        return peekElement(0);
    }

    /**
     * 查看指定偏移量的路径片段。
     *
     * @param offset 相对当前深度的偏移量
     * @return 对应的路径片段
     * @author GitHub Copilot
     * @reason 直接返回缓存好的格式化路径片段，替代原实现的 map 查询与 `StringBuffer` 拼接。
     */
    @Overwrite(remap = false)
    public String peekElement(final int offset) {
        if (offset < -pointer || offset > 0) {
            throw new ArrayIndexOutOfBoundsException(offset);
        }
        return ssoptimizer$formattedPathStack[pointer + offset - 1];
    }

    /**
     * 获取当前完整路径。
     *
     * @return 当前路径对象
     * @author GitHub Copilot
     * @reason 改为一次性复制已格式化路径片段构造 `Path`，避免循环调用 `peekElement()` 的重复开销。
     */
    @Overwrite(remap = false)
    public Path getPath() {
        if (currentPath == null) {
            currentPath = XStreamPathTrackerHelper.buildPath(ssoptimizer$formattedPathStack, pointer);
        }
        return currentPath;
    }
}