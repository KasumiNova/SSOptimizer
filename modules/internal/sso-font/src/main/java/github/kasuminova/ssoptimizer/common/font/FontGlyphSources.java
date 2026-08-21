package github.kasuminova.ssoptimizer.common.font;

import com.fs.graphics.font.BitmapFont;
import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import github.kasuminova.ssoptimizer.common.font.atlas.DynamicGlyphAtlas;
import github.kasuminova.ssoptimizer.common.font.layout.GlyphProvider;
import org.apache.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 字形来源解析门面：按字体身份（原版资源路径）决定 v2 渲染管线的字形源——
 * 覆盖表命中且 native 栅格化可用 → {@link TtfGlyphProvider}（动态图集）；
 * 否则 → {@link BitmapFontGlyphProvider}（原版 fnt 位图直发）。
 * <p>
 * 动机：布局引擎经 GlyphProvider 接口工作，「哪个字体走哪条路径」的判定
 * （含 TTF 源构造失败的回退）集中在此一处，Mixin 只调 {@link #resolve(BitmapFont)}。
 * <p>
 * 解析结果按 fontPath 缓存（BitmapFont 实例对同一路径在游戏生命周期内稳定）；
 * 动态图集为进程级共享单例，惰性创建时注册 GL 上下文重建监听。
 */
public final class FontGlyphSources {
    private static final Logger LOGGER = Logger.getLogger(FontGlyphSources.class);

    private static final Map<String, GlyphProvider> CACHE      = new ConcurrentHashMap<>();
    private static final Object                     ATLAS_LOCK = new Object();

    private static volatile DynamicGlyphAtlas sharedAtlas;

    private FontGlyphSources() {
    }

    /**
     * 解析一次 render 的字形来源。
     *
     * @param font 本次渲染的 BitmapFont（Mixin 已完成空字体防护）
     * @return TTF 动态图集源或位图源（永不返回 null）
     */
    public static GlyphProvider resolve(final BitmapFont font) {
        if (!FontRenderEngine.isV2()) {
            return new BitmapFontGlyphProvider(font);
        }
        // 渲染线程（RT 模式加载期 pump 路径会直接在游戏渲染代码里画文本）只走位图源：
        // TTF 源的图集请求会持有全局图集锁并可能经 GlDispatch.allocate 阻塞等渲染线程
        // drain——若调用方本身就是渲染线程即自死锁，若是主线程则与渲染线程形成
        // 「图集锁 vs 队列 drain」AB-BA 死锁（2026-08 实机冻结事故）。位图源是纯读 +
        // 流式发射，无锁无阻塞，与 P2 行为一致。
        if (GlDispatch.isRenderThread()) {
            return new BitmapFontGlyphProvider(font);
        }
        final String path = OriginalGameFontOverrides.normalize(font.getFontPath());
        if (path.isEmpty()) {
            return new BitmapFontGlyphProvider(font);
        }

        final GlyphProvider cached = CACHE.get(path);
        if (cached != null) {
            return cached;
        }
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(path, key -> createProvider(font, key));
        }
    }

    private static GlyphProvider createProvider(final BitmapFont font, final String path) {
        final OriginalGameFontOverrides.FontOverrideSpec spec = OriginalGameFontOverrides.specForPath(path);
        if (spec == null) {
            // 非覆盖表路径（mod 自带字体）：位图直发，行为与原版一致
            return new BitmapFontGlyphProvider(font);
        }
        if (!NativeFontRasterizer.isAvailable()) {
            LOGGER.info("[SSOptimizer] native 字体栅格化不可用，使用位图字形源: " + path);
            return new BitmapFontGlyphProvider(font);
        }
        try {
            final TtfGlyphProvider provider =
                    new TtfGlyphProvider(font, spec, OriginalGameFontOverrides.currentFontDir(), sharedAtlas());
            provider.startWarmup();
            LOGGER.info("[SSOptimizer] TTF 动态图集字形源就绪: " + path + " fontFile=" + provider.fontFileName());
            return provider;
        } catch (RuntimeException e) {
            LOGGER.warn("[SSOptimizer] TTF 字形源创建失败，回退位图字形源: " + path, e);
            return new BitmapFontGlyphProvider(font);
        }
    }

    /** 进程级共享图集（惰性创建）；创建时注册 GL 上下文重建监听。 */
    private static DynamicGlyphAtlas sharedAtlas() {
        DynamicGlyphAtlas atlas = sharedAtlas;
        if (atlas != null) {
            return atlas;
        }
        synchronized (ATLAS_LOCK) {
            if (sharedAtlas == null) {
                final DynamicGlyphAtlas created = new DynamicGlyphAtlas();
                GlDispatch.registerContextRecreatedListener(created::onContextRecreated);
                sharedAtlas = created;
            }
            return sharedAtlas;
        }
    }

    /** 测试用：清空解析缓存与共享图集引用，避免用例间静态状态串扰。 */
    static void resetForTests() {
        CACHE.clear();
        synchronized (ATLAS_LOCK) {
            sharedAtlas = null;
        }
    }
}
