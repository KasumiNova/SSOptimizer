package github.kasuminova.ssoptimizer.common.loading;

import com.fs.graphics.TextureLoader;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.apache.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

import java.io.IOException;
import java.nio.IntBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

final class TextureContextTracker {
    private static final Logger LOGGER = Logger.getLogger(TextureContextTracker.class);
    private static final int TARGET_2D = 3553;
    private static final int TEXTURE_BINDING_2D = 32873;
    private static final int INTERNAL_FORMAT_RGBA = 6408;
    private static final WeakKeyMap<com.fs.graphics.TextureObject, ContextBoundTextureEntry> CONTEXT_BOUND_TEXTURES =
            new WeakKeyMap<>();
    private static final ThreadLocal<Set<com.fs.graphics.TextureObject>> CONTEXT_RELOAD_GUARD =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final AtomicInteger CONTEXT_RELOAD_GUARD_ACTIVE = new AtomicInteger();
    private static final Object CONTEXT_GENERATION_LOCK = new Object();
    private static volatile Object lastOpenGlContextToken = null;
    private static volatile long currentOpenGlContextGeneration = 0L;

    private TextureContextTracker() {
    }

    static boolean shouldTrackContextBoundTexture(final String resourcePath) {
        return !TextureResourceLoader.normalizeResourcePath(resourcePath).isEmpty();
    }

    static void clearContextTracking() {
        synchronized (CONTEXT_BOUND_TEXTURES) {
            CONTEXT_BOUND_TEXTURES.clear();
        }
        CONTEXT_RELOAD_GUARD.remove();
        CONTEXT_RELOAD_GUARD_ACTIVE.set(0);
        TextureBinding.clearCurrentBoundTexture();
        lastOpenGlContextToken = null;
        currentOpenGlContextGeneration = 0L;
    }

    static void noteTextureLoadedForContext(final com.fs.graphics.TextureObject texture,
                                            final String resourcePath,
                                            final long contextGeneration) {
        storeContextBoundTextureEntry(texture, resourcePath, contextGeneration, true);
    }

    static boolean requiresContextReload(final com.fs.graphics.TextureObject texture,
                                         final long contextGeneration) {
        if (texture == null || contextGeneration <= 0L || isContextReloadInProgress(texture)) {
            return false;
        }
        synchronized (CONTEXT_BOUND_TEXTURES) {
            final ContextBoundTextureEntry tracked = CONTEXT_BOUND_TEXTURES.get(texture);
            return tracked != null && tracked.contextGeneration != contextGeneration;
        }
    }

    static long trackedContextGeneration(final com.fs.graphics.TextureObject texture) {
        synchronized (CONTEXT_BOUND_TEXTURES) {
            final ContextBoundTextureEntry tracked = CONTEXT_BOUND_TEXTURES.get(texture);
            return tracked == null ? 0L : tracked.contextGeneration;
        }
    }

    static <T> T withContextReloadGuard(final com.fs.graphics.TextureObject texture,
                                        final Supplier<T> action) {
        final boolean added = enterContextReloadGuard(texture);
        try {
            return action.get();
        } finally {
            exitContextReloadGuard(texture, added);
        }
    }

    static boolean hasCurrentOpenGlContext() {
        return currentOpenGlContextToken() != null;
    }

    static long observeCurrentOpenGlContextGeneration() {
        final Object currentToken = currentOpenGlContextToken();
        if (currentToken == null) {
            return currentOpenGlContextGeneration;
        }

        synchronized (CONTEXT_GENERATION_LOCK) {
            if (currentToken != lastOpenGlContextToken) {
                lastOpenGlContextToken = currentToken;
                currentOpenGlContextGeneration++;
                LOGGER.info("[SSOptimizer] Detected OpenGL context change; cached textures will reload on next bind (generation="
                        + currentOpenGlContextGeneration
                        + ", tracked=" + trackedContextBoundTextureCount() + ')');
            }
            return currentOpenGlContextGeneration;
        }
    }

    private static Object currentOpenGlContextToken() {
        try {
            return GLContext.getCapabilities();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("[SSOptimizer] OpenGL context is unavailable: " + e.getMessage(), e);
            return null;
        }
    }

    static int captureBoundTexture(final int target) {
        final int bindingParameter = bindingParameterForTarget(target);
        if (bindingParameter == Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (RenderThreadMode.isEnabled()) {
            // 查询绑定会 drain 全管线，直接返回哨兵使 restore 空转。
            return Integer.MIN_VALUE;
        }
        try {
            return GL11.glGetInteger(bindingParameter);
        } catch (RuntimeException e) {
            LOGGER.warn("[SSOptimizer] Failed to query the current texture binding for target " + target, e);
            return Integer.MIN_VALUE;
        }
    }

    static void restoreBoundTexture(final int target,
                                    final int previousBinding) {
        if (previousBinding == Integer.MIN_VALUE) {
            return;
        }
        GL11.glBindTexture(target, Math.max(previousBinding, 0));
    }

    private static int bindingParameterForTarget(final int target) {
        if (target == TARGET_2D) {
            return TEXTURE_BINDING_2D;
        }
        return Integer.MIN_VALUE;
    }

    static com.fs.graphics.TextureObject markTextureLoadedInCurrentContext(
            final com.fs.graphics.TextureObject texture,
            final String resourcePath) {
        if (texture == null) {
            return null;
        }
        storeContextBoundTextureEntry(texture, resourcePath, observeCurrentOpenGlContextGeneration(), true);
        return texture;
    }

    static void ensureContextBoundTextureTracked(final com.fs.graphics.TextureObject texture,
                                                 final String resourcePath) {
        storeContextBoundTextureEntry(texture, resourcePath, observeCurrentOpenGlContextGeneration(), false);
    }

    private static void storeContextBoundTextureEntry(final com.fs.graphics.TextureObject texture,
                                                      final String resourcePath,
                                                      final long contextGeneration,
                                                      final boolean replaceGeneration) {
        if (texture == null || !shouldTrackContextBoundTexture(resourcePath)) {
            return;
        }

        final String normalizedPath = TextureResourceLoader.normalizeResourcePath(resourcePath);
        synchronized (CONTEXT_BOUND_TEXTURES) {
            final ContextBoundTextureEntry existing = CONTEXT_BOUND_TEXTURES.get(texture);
            if (existing == null) {
                CONTEXT_BOUND_TEXTURES.put(texture, new ContextBoundTextureEntry(normalizedPath, contextGeneration));
                return;
            }

            final long generation = replaceGeneration ? contextGeneration : existing.contextGeneration;
            final String path = normalizedPath.isEmpty() ? existing.resourcePath : normalizedPath;
            CONTEXT_BOUND_TEXTURES.put(texture, new ContextBoundTextureEntry(path, generation));
        }
    }

    private static int trackedContextBoundTextureCount() {
        synchronized (CONTEXT_BOUND_TEXTURES) {
            return CONTEXT_BOUND_TEXTURES.size();
        }
    }

    static boolean isContextReloadInProgress(final com.fs.graphics.TextureObject texture) {
        return texture != null
                && CONTEXT_RELOAD_GUARD_ACTIVE.get() > 0
                && CONTEXT_RELOAD_GUARD.get().contains(texture);
    }

    private static boolean enterContextReloadGuard(final com.fs.graphics.TextureObject texture) {
        if (texture == null) {
            return false;
        }
        final boolean added = CONTEXT_RELOAD_GUARD.get().add(texture);
        if (added) {
            CONTEXT_RELOAD_GUARD_ACTIVE.incrementAndGet();
        }
        return added;
    }

    private static void exitContextReloadGuard(final com.fs.graphics.TextureObject texture,
                                               final boolean added) {
        final Set<com.fs.graphics.TextureObject> guardedTextures = CONTEXT_RELOAD_GUARD.get();
        if (added) {
            guardedTextures.remove(texture);
            CONTEXT_RELOAD_GUARD_ACTIVE.decrementAndGet();
        }
        if (guardedTextures.isEmpty()) {
            CONTEXT_RELOAD_GUARD.remove();
        }
    }

    static void reloadTextureForCurrentContext(final com.fs.graphics.TextureObject texture,
                                               final int target,
                                               final long now,
                                               final boolean restoreBinding,
                                               final long contextGeneration) {
        if (!hasCurrentOpenGlContext()) {
            LOGGER.debug("[SSOptimizer] Skipped context reload without current OpenGL context for texture " + texture);
            return;
        }

        final ContextBoundTextureEntry tracked;
        synchronized (CONTEXT_BOUND_TEXTURES) {
            tracked = CONTEXT_BOUND_TEXTURES.get(texture);
        }
        if (tracked == null || tracked.resourcePath == null || tracked.resourcePath.isBlank()) {
            return;
        }

        final int previousBinding = restoreBinding ? captureBoundTexture(target) : Integer.MIN_VALUE;
        final boolean guarded = enterContextReloadGuard(texture);
        // 旧纹理 id 随旧 GL 上下文销毁，无需也不能再 glDeleteTextures。
        try {
            reloadTextureInPlace(texture, target, tracked.resourcePath);
            TextureResidency.markResident(texture, now);
            storeContextBoundTextureEntry(texture, tracked.resourcePath, contextGeneration, true);
            LOGGER.debug("[SSOptimizer] Reloaded cached texture after OpenGL context change: " + tracked.resourcePath);
        } catch (IOException e) {
            LOGGER.error("[SSOptimizer] Failed to reload cached texture after OpenGL context change: " + tracked.resourcePath, e);
        } finally {
            exitContextReloadGuard(texture, guarded);
            if (restoreBinding) {
                restoreBoundTexture(target, previousBinding);
            }
        }
    }

    private static void reloadTextureInPlace(final com.fs.graphics.TextureObject texture,
                                             final int target,
                                             final String resourcePath) throws IOException {
        final IntBuffer ids = BufferUtils.createIntBuffer(1);
        GL11.glGenTextures(ids);
        final int textureId = ids.get(0);
        TextureResidency.setTextureId(texture, textureId);

        try {
            new TextureLoader().loadTextureData(
                    texture,
                    resourcePath,
                    target,
                    INTERNAL_FORMAT_RGBA,
                    TextureBinding.minFilterForResourcePath(resourcePath),
                    TextureBinding.magFilterForResourcePath(resourcePath),
                    false
            );
        } catch (IOException e) {
            try {
                GL11.glDeleteTextures(textureId);
            } finally {
                TextureResidency.setTextureId(texture, -1);
            }
            throw e;
        }
    }

    static String trackedResourcePath(final com.fs.graphics.TextureObject texture) {
        synchronized (CONTEXT_BOUND_TEXTURES) {
            final ContextBoundTextureEntry tracked = CONTEXT_BOUND_TEXTURES.get(texture);
            return tracked == null ? null : tracked.resourcePath;
        }
    }

    private record ContextBoundTextureEntry(String resourcePath,
                                            long contextGeneration) {
    }
}
