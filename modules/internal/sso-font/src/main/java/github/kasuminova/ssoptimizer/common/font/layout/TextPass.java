package github.kasuminova.ssoptimizer.common.font.layout;

import java.util.List;

/**
 * 一次文本绘制 pass：对应原版 {@code drawText} 单次调用（含 compact 字体的每次重复迭代）
 * 产出的全部字形 quad，发射时独占一段 glBegin/glEnd。
 * <p>
 * pass 有序：边框 4 pass（+x/-x/+y/-y）→ 或阴影 1 pass → 主 pass，紧凑字体每逻辑 pass 重复 3 次，
 * 顺序即渲染层级顺序。
 */
public record TextPass(List<GlyphQuad> quads) {
}
