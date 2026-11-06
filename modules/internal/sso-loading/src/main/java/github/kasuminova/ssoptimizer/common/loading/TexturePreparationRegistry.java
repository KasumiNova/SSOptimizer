package github.kasuminova.ssoptimizer.common.loading;

import org.apache.log4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

public final class TexturePreparationRegistry {
    private static final Logger LOGGER = Logger.getLogger(TexturePreparationRegistry.class);

    public static final String DISABLE_PROPERTY = "ssoptimizer.disable.texturepreparation";

    private static final ConcurrentMap<String, CompletableFuture<Prepared>> PENDING = new ConcurrentHashMap<>();

    private TexturePreparationRegistry() {
    }

    public record Prepared(String sourceHash,
                           long sourceByteLength,
                           TextureConversionCache.CachedTextureMetadata metadata) {
    }

    public static boolean isEnabled() {
        return TextureConversionCache.isEnabled() && !Boolean.getBoolean(DISABLE_PROPERTY);
    }

    public static void track(final String path) {
        if (path == null || !isEnabled()) {
            return;
        }
        PENDING.putIfAbsent(path, new CompletableFuture<>());
    }

    public static void complete(final String path, final Prepared prepared) {
        if (path == null) {
            return;
        }
        final CompletableFuture<Prepared> future = PENDING.get(path);
        if (future != null) {
            future.complete(prepared);
        }
    }

    public static Prepared await(final String path) {
        if (path == null || !isEnabled()) {
            return null;
        }

        final CompletableFuture<Prepared> future = PENDING.get(path);
        if (future == null) {
            return null;
        }

        try {
            return future.get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (final ExecutionException e) {
            LOGGER.warn("[SSOptimizer] Texture preparation failed for " + path + ": " + e.getCause());
            return null;
        } finally {
            PENDING.remove(path);
        }
    }

    public static void clear() {
        PENDING.clear();
    }
}
