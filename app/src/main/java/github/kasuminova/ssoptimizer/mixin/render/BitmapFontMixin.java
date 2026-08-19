package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.font.BitmapGlyph;
import com.fs.graphics.font.FontGlyph;
import com.fs.util.container.Pair;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

/**
 * BitmapFont.getKerning 的线程安全重写。
 * <p>
 * 注入目标：{@code com.fs.graphics.font.BitmapFont}<br>
 * 注入动机：原版用共享可变字段 {@code kerningLookupPair} 作为 kerningMap 的查询键，
 * 每次查询都写入 pair.one/two。字体对象是启动期单例，并行录制期多个 worker 渲染
 * 共享同一字体的文本时并发互踩该字段，kerning 查询读到撕裂的键（错误间距/错字形）。<br>
 * 注入效果：{@code getKerning} 整体替换为线程局部 Pair 查询键，语义与原版一致。
 */
@Mixin(targets = GameClassNames.BITMAP_FONT_DOTTED)
public abstract class BitmapFontMixin {

    @Shadow(remap = false)
    private BitmapGlyph[] glyphs;

    @Shadow(remap = false)
    private Map<Pair, FontGlyph> kerningMap;

    /** 线程局部查询键：替代原版共享的 kerningLookupPair（并行录制线程安全）。 */
    private final ThreadLocal<Pair> ssoptimizer$kerningLookupPair = ThreadLocal.withInitial(Pair::new);

    /**
     * @author SSOptimizer
     * @reason 原版共享查询键在并行录制期被多 worker 并发写入，kerning 查询结果撕裂
     */
    @Overwrite(remap = false)
    public FontGlyph getKerning(int i, int j) {
        if (i < this.glyphs.length && j < this.glyphs.length) {
            Pair lookup = ssoptimizer$kerningLookupPair.get();
            lookup.one = this.glyphs[i];
            lookup.two = this.glyphs[j];
            return this.kerningMap.get(lookup);
        }
        return null;
    }
}
