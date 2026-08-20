package github.kasuminova.ssoptimizer.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import javax.xml.stream.XMLStreamWriter;

/**
 * txw2 委托 XML 写入器访问器。
 * <p>
 * 注入目标：{@code com.sun.xml.txw2.output.DelegatingXMLStreamWriter}<br>
 * 注入动机：紧凑写出优化需要直接访问底层 {@link XMLStreamWriter}，而 txw2 原类将其保存在私有字段中。<br>
 * 注入效果：向 Mixin 层暴露底层委托写入器，供保存路径上的紧凑直写逻辑使用。
 */
@Mixin(targets = "com.sun.xml.txw2.output.DelegatingXMLStreamWriter")
public interface Txw2DelegatingXmlStreamWriterAccessor {
    /**
     * 获取底层委托写入器。
     *
     * @return 原始 XMLStreamWriter
     */
    @Accessor("writer")
    XMLStreamWriter ssoptimizer$getWriter();

    /**
     * 替换底层委托写入器。
     *
     * @param writer 新的 XML 写入器
     */
    @Mutable
    @Accessor("writer")
    void ssoptimizer$setWriter(XMLStreamWriter writer);
}