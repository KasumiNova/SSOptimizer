package github.kasuminova.ssoptimizer.mixin.save;

import github.kasuminova.ssoptimizer.common.save.XStreamObjectIdDictionaryHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * XStream 对象引用字典查询加速 Mixin。
 * <p>
 * 注入目标：{@code com.thoughtworks.xstream.core.util.ObjectIdDictionary}<br>
 * 注入动机：热点分析显示该类在 `lookupId/containsId/removeId` 中每次都会新建一个查询包装对象，
 * 在大对象图的引用跟踪阶段会产生稳定但纯额外的分配与哈希开销。<br>
 * 注入效果：改为在每个字典实例上绑定一个可复用探针执行查询，既保留原始弱引用存储模型与对象标识语义，
 * 又避开热点路径上的 {@link ThreadLocal#get()} 开销。
 */
@Mixin(targets = "com.thoughtworks.xstream.core.util.ObjectIdDictionary")
public abstract class XStreamObjectIdDictionaryMixin {
    @Mutable
    @Final
    @Shadow(remap = false)
    private Map<?, ?> map;

    @Unique
    private static final int SSOPTIMIZER_INITIAL_REFERENCE_MAP_CAPACITY = 4096;

    @Unique
    private final XStreamObjectIdDictionaryHelper.ReusableIdProbe ssoptimizer$lookupProbe =
            XStreamObjectIdDictionaryHelper.createReusableProbe();

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void ssoptimizer$preSizeReferenceMap(final CallbackInfo callbackInfo) {
        map = new HashMap<>(SSOPTIMIZER_INITIAL_REFERENCE_MAP_CAPACITY);
    }

    /**
     * 查询对象对应的引用 ID。
     *
     * @param item 待查询对象
     * @return 命中的引用 ID；若不存在则返回 {@code null}
     * @author GitHub Copilot
     * @reason 复用字典实例级探针 key，避免每次查询都分配新的 `IdWrapper`，并去掉热点上的 ThreadLocal 读取。
     */
    @Overwrite(remap = false)
    public Object lookupId(final Object item) {
        return XStreamObjectIdDictionaryHelper.lookupId(map, item, ssoptimizer$lookupProbe);
    }

    /**
     * 判断对象是否已有引用 ID。
     *
     * @param item 待查询对象
     * @return 若已存在引用 ID 则返回 {@code true}
     * @author GitHub Copilot
     * @reason 复用字典实例级探针 key，去掉纯查询路径上的包装对象分配与 ThreadLocal 读取。
     */
    @Overwrite(remap = false)
    public boolean containsId(final Object item) {
        return XStreamObjectIdDictionaryHelper.containsId(map, item, ssoptimizer$lookupProbe);
    }

    /**
     * 删除对象对应的引用 ID。
     *
     * @param item 待删除对象
     * @author GitHub Copilot
     * @reason 复用字典实例级探针 key，降低引用字典清理阶段的查询分配成本与 ThreadLocal 读取开销。
     */
    @Overwrite(remap = false)
    public void removeId(final Object item) {
        XStreamObjectIdDictionaryHelper.removeId(map, item, ssoptimizer$lookupProbe);
    }
}