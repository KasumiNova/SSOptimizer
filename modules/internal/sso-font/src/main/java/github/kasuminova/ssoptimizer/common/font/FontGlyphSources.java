package github.kasuminova.ssoptimizer.common.font;

import com.fs.graphics.font.BitmapFont;
import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import github.kasuminova.ssoptimizer.common.font.atlas.DynamicGlyphAtlas;
import github.kasuminova.ssoptimizer.common.font.layout.GlyphProvider;
import org.apache.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FontGlyphSources {
    private static final Logger LOGGER = Logger.getLogger(FontGlyphSources.class);

    private static final Map<String, GlyphProvider> CACHE      = new ConcurrentHashMap<>();
    private static final Object                     ATLAS_LOCK = new Object();

    private static volatile DynamicGlyphAtlas sharedAtlas;

    private FontGlyphSources() {
    }

    public static GlyphProvider resolve(final BitmapFont font) {
        // 渲染线程持图集锁等待自身 drain 会自死锁，主线程也可能与渲染线程形成锁序倒置，因此渲染线程只使用无锁位图源。
        if (!OriginalGameFontOverrides.isEnabled() || GlDispatch.isRenderThread()) {
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

    static void resetForTests() {
        CACHE.clear();
        synchronized (ATLAS_LOCK) {
            sharedAtlas = null;
        }
    }
}
