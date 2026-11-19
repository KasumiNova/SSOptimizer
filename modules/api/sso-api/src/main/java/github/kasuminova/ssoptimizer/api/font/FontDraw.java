package github.kasuminova.ssoptimizer.api.font;

import java.util.List;
import java.util.function.Function;

/**
 * 提交已布局字形的绘制，使渲染线程批量解码四边形。未注册时，调用方应继续录制顶点流。
 */
public interface FontDraw {
    /**
     * 在主线程提交非空绘制。列表和提取函数仅在调用期间读取，字形必须不可变。
     * 使用图集时启用并切换纹理；使用位图字体时，调用方必须先启用并绑定字体纹理。
     * 当前录制段不支持独占命令或绘制层均为空时返回 false，调用方应继续录制顶点流。
     */
    <P> boolean trySubmit(List<P> passes, Function<? super P, ? extends List<? extends GlyphVertices>> glyphs,
                          boolean atlas, int blendSrc, int blendDst);

    /** 按原绘制顺序提供四组位置及 UV、ARGB 颜色及纹理 ID。实例必须允许渲染线程延后读取。 */
    interface GlyphVertices {
        float x1();
        float y1();
        float u1();
        float v1();

        float x2();
        float y2();
        float u2();
        float v2();

        float x3();
        float y3();
        float u3();
        float v3();

        float x4();
        float y4();
        float u4();
        float v4();

        int color();
        int textureId();
    }
}
