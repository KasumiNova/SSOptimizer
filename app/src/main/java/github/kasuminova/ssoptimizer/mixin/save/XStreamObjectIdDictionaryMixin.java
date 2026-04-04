package github.kasuminova.ssoptimizer.mixin.save;

import github.kasuminova.ssoptimizer.common.save.XStreamObjectIdDictionaryHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

/**
 * XStream 对象引用字典查询加速 Mixin。
 * <p>
 * 注入目标：{@code com.thoughtworks.xstream.core.util.ObjectIdDictionary}<br>
 * 注入动机：热点分析显示该类在 `lookupId/containsId/removeId` 中每次都会新建一个查询包装对象，
 * 在大对象图的引用跟踪阶段会产生稳定但纯额外的分配与哈希开销。<br>
 * 注入效果：改为使用线程本地可复用探针执行查询，保留原始弱引用存储模型与对象标识语义不变。
 */
@Mixin(targets = "com.thoughtworks.xstream.core.util.ObjectIdDictionary")
public abstract class XStreamObjectIdDictionaryMixin {
    @Shadow(remap = false)
    private Map<?, ?> map;

    /**
     * 查询对象对应的引用 ID。
     *
     * @param item 待查询对象
     * @return 命中的引用 ID；若不存在则返回 {@code null}
     * @author GitHub Copilot
     * @reason 复用线程本地探针 key，避免每次查询都分配新的 `IdWrapper`。
     */
    @Overwrite(remap = false)
    public Object lookupId(final Object item) {
        return XStreamObjectIdDictionaryHelper.lookupId(map, item);
    }

    /**
     * 判断对象是否已有引用 ID。
     *
     * @param item 待查询对象
     * @return 若已存在引用 ID 则返回 {@code true}
     * @author GitHub Copilot
     * @reason 复用线程本地探针 key，去掉纯查询路径上的包装对象分配。
     */
    @Overwrite(remap = false)
    public boolean containsId(final Object item) {
        return XStreamObjectIdDictionaryHelper.containsId(map, item);
    }

    /**
     * 删除对象对应的引用 ID。
     *
     * @param item 待删除对象
     * @author GitHub Copilot
     * @reason 复用线程本地探针 key，降低引用字典清理阶段的查询分配成本。
     */
    @Overwrite(remap = false)
    public void removeId(final Object item) {
        XStreamObjectIdDictionaryHelper.removeId(map, item);
    }
}