package github.kasuminova.ssoptimizer.mixin.save;

import com.thoughtworks.xstream.core.ReferenceByIdMarshaller;
import com.thoughtworks.xstream.io.path.Path;
import github.kasuminova.ssoptimizer.common.save.XStreamReferenceIdHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * XStream 引用 ID 分配优化 Mixin。
 * <p>
 * 注入目标：{@code com.thoughtworks.xstream.core.ReferenceByIdMarshaller}<br>
 * 注入动机：更新后的热点报告显示默认 {@code SequenceGenerator.next()} 的十进制字符串分配已经成为保存阶段的头号热点；
 * 这些引用 ID 仅用于同一份 XML 内部的引用匹配，不需要保留十进制格式。<br>
 * 注入效果：当 marshaller 使用 XStream 默认 {@code SequenceGenerator} 时，改为生成更紧凑的 base36 引用 ID 对象；
 * 若上游传入自定义 {@code IDGenerator}，则仍完整回退到原始逻辑。
 */
@Mixin(targets = "com.thoughtworks.xstream.core.ReferenceByIdMarshaller")
public abstract class XStreamReferenceByIdMarshallerMixin {
    @Shadow(remap = false)
    private ReferenceByIdMarshaller.IDGenerator idGenerator;

    @Unique
    private boolean ssoptimizer$optimizedSequenceInitialized;

    @Unique
    private int ssoptimizer$nextReferenceId;

    /**
     * 为当前对象生成引用 key。
     *
     * @param path 当前 XStream 路径
     * @param item 当前对象
     * @return 引用字典使用的 key
     * @author GitHub Copilot
     * @reason 当使用默认 SequenceGenerator 时，改为更紧凑的 base36 引用 ID，减少保存热点里的整数转字符串成本。
     */
    @Overwrite(remap = false)
    protected Object createReferenceKey(final Path path,
                                        final Object item) {
        final ReferenceByIdMarshaller.IDGenerator generator = idGenerator;
        if (XStreamReferenceIdHelper.supportsOptimizedIds(generator)) {
            if (!ssoptimizer$optimizedSequenceInitialized) {
                ssoptimizer$nextReferenceId = XStreamReferenceIdHelper.readSequenceCounter(generator);
                ssoptimizer$optimizedSequenceInitialized = true;
            }
            return XStreamReferenceIdHelper.nextReferenceId(ssoptimizer$nextReferenceId++);
        }
        return generator.next(item);
    }
}