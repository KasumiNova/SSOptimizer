package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.render.atlas.ShipWeaponAtlas;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class TextureBinding {
    private static final Logger LOGGER = Logger.getLogger(TextureBinding.class);
    private static final int FILTER_LINEAR = 9729;
    private static final String FONTS_PREFIX = "graphics/fonts/";
    private static final String INSIGNIA_PREFIX = FONTS_PREFIX + "insignia";
    private static final String ORBITRON_PREFIX = FONTS_PREFIX + "orbitron";
    private static final String VICTOR_PREFIX = FONTS_PREFIX + "victor";
    private static final ThreadLocal<String> CURRENT_BOUND_TEXTURE_PATH = ThreadLocal.withInitial(() -> "");
    private static final AtomicLong BIND_STATS_CALLS = new AtomicLong();
    private static final AtomicLong BIND_STATS_REAL = new AtomicLong();
    private static final AtomicLong BIND_STATS_DEDUPED = new AtomicLong();
    private static final AtomicLong BIND_STATS_ATLAS = new AtomicLong();
    private static final boolean BIND_PATH_STATS = Boolean.getBoolean("ssoptimizer.bindstats.paths");
    private static final ConcurrentHashMap<String, AtomicLong> BIND_PATH_COUNTS =
            BIND_PATH_STATS ? new ConcurrentHashMap<>() : null;
    private static final String TRACE_PATH = System.getProperty(LazyTextureManager.TRACE_PATH_PROPERTY, "");
    private static final Set<String> TRACE_ALL_SEEN = ConcurrentHashMap.newKeySet();
    private static volatile long nextBindStatsLogNanos = 0L;

    private TextureBinding() {
    }

    static void bindTexture(final com.fs.graphics.TextureObject texture,
                            final int target) {
        BIND_STATS_CALLS.incrementAndGet();
        final ShipWeaponAtlas.Region atlasRegion = ShipWeaponAtlas.lookup(texture.getTexturePath());
        if (atlasRegion != null) {
            traceTextureAccess("bindTexture", texture, atlasRegion.textureId(), true);
            bindTextureDeduped(target, atlasRegion.textureId());
            noteCurrentBoundTexture(texture);
            BIND_STATS_ATLAS.incrementAndGet();
            maybeLogBindStats();
            return;
        }
        if (TextureContextTracker.isContextReloadInProgress(texture)) {
            traceTextureAccess("bindTexture", texture,
                    Math.max(TextureResidency.readTextureId(texture, -1), 0), false);
            bindTextureDeduped(target, Math.max(TextureResidency.readTextureId(texture, -1), 0));
            noteCurrentBoundTexture(texture);
            noteBindPath(texture);
            maybeLogBindStats();
            return;
        }

        final long now = System.nanoTime();
        TextureResidency.ensureTextureReady(texture, target, now, false);

        final int textureId = TextureResidency.readTextureId(texture, -1);
        traceTextureAccess("bindTexture", texture, Math.max(textureId, 0), false);
        bindTextureDeduped(target, Math.max(textureId, 0));
        noteCurrentBoundTexture(texture);
        noteBindPath(texture);
        maybeLogBindStats();
        TextureResidency.maybeSweepIdleTextures(texture, now);
        TextureResidency.drainCompressionUpgrades(target, Math.max(textureId, 0), now);
        TextureResidency.maybeEmitTextureDiagnostics(now);
    }

    private static void noteBindPath(final com.fs.graphics.TextureObject texture) {
        if (BIND_PATH_COUNTS == null) {
            return;
        }
        final String path = texture.getTexturePath();
        BIND_PATH_COUNTS.computeIfAbsent(path == null ? "<null>" : path, key -> new AtomicLong())
                .incrementAndGet();
    }

    // 渲染分离下 glGetInteger 会 drain 全管线，必须直接录制 bind。
    private static void bindTextureDeduped(final int target, final int textureId) {
        if (RenderThreadMode.isEnabled()) {
            GL11.glBindTexture(target, textureId);
            BIND_STATS_REAL.incrementAndGet();
            return;
        }
        if (target == GL11.GL_TEXTURE_2D
                && GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == textureId) {
            BIND_STATS_DEDUPED.incrementAndGet();
            return;
        }
        GL11.glBindTexture(target, textureId);
        BIND_STATS_REAL.incrementAndGet();
    }

    static boolean isCurrentBoundVictorPixelFontTexture() {
        return isVictorPixelFontTexture(CURRENT_BOUND_TEXTURE_PATH.get());
    }

    static boolean isCurrentBoundManagedFontTexture() {
        return isManagedFontTexture(CURRENT_BOUND_TEXTURE_PATH.get());
    }

    // 裸 UV 调用方必须获得独立纹理 id，图集 id 只允许由 Sprite 渲染路径解析。
    static int getTextureId(final com.fs.graphics.TextureObject texture,
                            final int target,
                            final int currentTextureId) {
        if (texture == null) {
            return currentTextureId;
        }
        if (TextureContextTracker.isContextReloadInProgress(texture)) {
            traceTextureAccess("getTextureId", texture, currentTextureId, false);
            return currentTextureId;
        }

        final long now = System.nanoTime();
        final int ensuredTextureId = TextureResidency.ensureTextureReady(texture, target, now, true);
        TextureResidency.noteTextureIdExternalized(texture, now);
        TextureResidency.maybeSweepIdleTextures(texture, now);
        TextureResidency.maybeEmitTextureDiagnostics(now);
        final int result = ensuredTextureId >= 0 ? ensuredTextureId : currentTextureId;
        traceTextureAccess("getTextureId", texture, result, false);
        return result;
    }

    private static void traceTextureAccess(final String operation,
                                           final com.fs.graphics.TextureObject texture,
                                           final int resultId,
                                           final boolean atlasHit) {
        if (TRACE_PATH.isEmpty() || texture == null) {
            return;
        }
        final String path = texture.getTexturePath();
        if ("ALL".equals(TRACE_PATH)) {
            if (!TRACE_ALL_SEEN.add(path == null ? "<null>" : path)) {
                return;
            }
        } else if (path == null || !path.contains(TRACE_PATH)) {
            return;
        }
        final StringBuilder caller = new StringBuilder();
        if ("ALL".equals(TRACE_PATH)) {
            for (StackTraceElement frame : new Throwable().getStackTrace()) {
                final String className = frame.getClassName();
                if (className.startsWith("java.") || className.startsWith("github.kasuminova.ssoptimizer")) {
                    continue;
                }
                caller.append(className).append('.').append(frame.getMethodName()).append(" <= ");
                if (caller.length() > 240) {
                    break;
                }
            }
        }
        LOGGER.info("[SSOptimizer][textrace] " + operation + " path=" + path
                + " resultId=" + resultId + " atlasHit=" + atlasHit
                + " fieldId=" + TextureResidency.readTextureId(texture, -1)
                + " entry=" + TextureResidency.traceState(texture)
                + " thread=" + Thread.currentThread().getName()
                + " identity=" + System.identityHashCode(texture)
                + (caller.length() == 0 ? "" : " caller=" + caller));
    }

    private static void maybeLogBindStats() {
        final long now = System.nanoTime();
        if (now < nextBindStatsLogNanos) {
            return;
        }
        nextBindStatsLogNanos = now + 10_000_000_000L;
        LOGGER.info("[SSOptimizer] texture binds: calls=" + BIND_STATS_CALLS.get()
                + " real=" + BIND_STATS_REAL.get()
                + " deduped=" + BIND_STATS_DEDUPED.get()
                + " atlas=" + BIND_STATS_ATLAS.get());
        if (BIND_PATH_COUNTS != null) {
            final StringBuilder top = new StringBuilder("[SSOptimizer] top non-atlas bind paths:");
            BIND_PATH_COUNTS.entrySet().stream()
                    .sorted(Map.Entry.<String, AtomicLong>comparingByValue(
                            (left, right) -> Long.compare(right.get(), left.get())))
                    .limit(15)
                    .forEach(entry -> top.append(' ').append(entry.getKey()).append('=').append(entry.getValue().get()));
            LOGGER.info(top.toString());
        }
    }

    static int minFilterForResourcePath(final String resourcePath) {
        return FILTER_LINEAR;
    }

    static int magFilterForResourcePath(final String resourcePath) {
        return FILTER_LINEAR;
    }

    static boolean isVictorPixelFontTexture(final String resourcePath) {
        return false;
    }

    static boolean isManagedVictorFontTexture(final String resourcePath) {
        final String normalized = TextureResourceLoader.normalizeResourcePath(resourcePath);
        return (normalized.startsWith(VICTOR_PREFIX)
                || normalized.startsWith("ssoptimizer/runtimefonts/" + VICTOR_PREFIX))
                && normalized.toLowerCase(Locale.ROOT).endsWith(".png");
    }

    static boolean isManagedFontTexture(final String resourcePath) {
        return isManagedVictorFontTexture(resourcePath) || isSharpenedUiFontTexture(resourcePath);
    }

    private static void noteCurrentBoundTexture(final com.fs.graphics.TextureObject texture) {
        CURRENT_BOUND_TEXTURE_PATH.set(resolveTrackedResourcePath(texture));
    }

    private static String resolveTrackedResourcePath(final com.fs.graphics.TextureObject texture) {
        if (texture == null) {
            return "";
        }

        final String managedPath = TextureResidency.managedResourcePath(texture);
        if (managedPath != null && !managedPath.isBlank()) {
            return managedPath;
        }

        final String contextPath = TextureContextTracker.trackedResourcePath(texture);
        return contextPath == null ? "" : contextPath;
    }

    static boolean isSharpenedUiFontTexture(final String resourcePath) {
        final String normalized = TextureResourceLoader.normalizeResourcePath(resourcePath).toLowerCase(Locale.ROOT);
        return normalized.endsWith(".png")
                && (normalized.startsWith(INSIGNIA_PREFIX)
                || normalized.startsWith(ORBITRON_PREFIX)
                || normalized.startsWith("ssoptimizer/runtimefonts/graphics/fonts/insignia")
                || normalized.startsWith("ssoptimizer/runtimefonts/graphics/fonts/orbitron"));
    }

    static boolean isFontAtlasWithoutMipmaps(final String resourcePath) {
        return isManagedVictorFontTexture(resourcePath) || isSharpenedUiFontTexture(resourcePath);
    }

    static void clearCurrentBoundTexture() {
        CURRENT_BOUND_TEXTURE_PATH.remove();
    }
}
