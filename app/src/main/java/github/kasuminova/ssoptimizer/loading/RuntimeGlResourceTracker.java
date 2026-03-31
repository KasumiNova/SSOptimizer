package github.kasuminova.ssoptimizer.loading;

import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks live OpenGL resources that are created outside the file-backed texture
 * metadata pipeline so we can estimate the unmanaged VRAM gap at runtime.
 */
public final class RuntimeGlResourceTracker {
    static final String SUMMARY_LOG_INTERVAL_MILLIS_PROPERTY = "ssoptimizer.runtimegl.logintervalmillis";
    static final long   DEFAULT_SUMMARY_LOG_INTERVAL_MILLIS  = 15_000L;

    private static final Logger LOGGER = Logger.getLogger(RuntimeGlResourceTracker.class);

    private static final int GL_TEXTURE_BINDING_2D          = 0x8069;
    private static final int GL_TEXTURE_BINDING_3D          = 0x806A;
    private static final int GL_TEXTURE_BINDING_CUBE_MAP    = 0x8514;
    private static final int GL_TEXTURE_CUBE_MAP            = 0x8513;
    private static final int GL_TEXTURE_CUBE_MAP_POSITIVE_X = 0x8515;
    private static final int GL_TEXTURE_CUBE_MAP_NEGATIVE_Z = 0x851A;

    private static final int GL_UNSIGNED_BYTE  = 0x1401;
    private static final int GL_BYTE           = 0x1400;
    private static final int GL_UNSIGNED_SHORT = 0x1403;
    private static final int GL_SHORT          = 0x1402;
    private static final int GL_UNSIGNED_INT   = 0x1405;
    private static final int GL_INT            = 0x1404;
    private static final int GL_FLOAT          = 0x1406;
    private static final int GL_HALF_FLOAT     = 0x140B;

    private static final int GL_RED             = 0x1903;
    private static final int GL_RG              = 0x8227;
    private static final int GL_RGB             = 0x1907;
    private static final int GL_RGBA            = 0x1908;
    private static final int GL_DEPTH_COMPONENT = 0x1902;
    private static final int GL_DEPTH_STENCIL   = 0x84F9;
    private static final int GL_STENCIL_INDEX   = 0x1901;

    private static final int GL_R8                 = 0x8229;
    private static final int GL_R16                = 0x822A;
    private static final int GL_R16F               = 0x822D;
    private static final int GL_R32F               = 0x822E;
    private static final int GL_RG8                = 0x822B;
    private static final int GL_RG16               = 0x822C;
    private static final int GL_RG16F              = 0x822F;
    private static final int GL_RG32F              = 0x8230;
    private static final int GL_RGB8               = 0x8051;
    private static final int GL_RGB10              = 0x8052;
    private static final int GL_RGB12              = 0x8053;
    private static final int GL_RGB16              = 0x8054;
    private static final int GL_RGB16F             = 0x881B;
    private static final int GL_RGB32F             = 0x8815;
    private static final int GL_RGBA8              = 0x8058;
    private static final int GL_RGB10_A2           = 0x8059;
    private static final int GL_RGBA12             = 0x805A;
    private static final int GL_RGBA16             = 0x805B;
    private static final int GL_SRGB8_ALPHA8       = 0x8C43;
    private static final int GL_RGBA16F            = 0x881A;
    private static final int GL_RGBA32F            = 0x8814;
    private static final int GL_DEPTH_COMPONENT16  = 0x81A5;
    private static final int GL_DEPTH_COMPONENT24  = 0x81A6;
    private static final int GL_DEPTH_COMPONENT32  = 0x81A7;
    private static final int GL_DEPTH_COMPONENT32F = 0x8CAC;
    private static final int GL_DEPTH24_STENCIL8   = 0x88F0;
    private static final int GL_DEPTH32F_STENCIL8  = 0x8CAD;
    private static final int GL_STENCIL_INDEX8     = 0x8D48;

    private static final Map<Integer, TextureAllocation>      TEXTURES      = new ConcurrentHashMap<>();
    private static final Map<Integer, RenderbufferAllocation> RENDERBUFFERS = new ConcurrentHashMap<>();

    private static volatile long nextSummaryNanos = 0L;
    private static volatile long peakLiveBytes    = 0L;
    private static volatile long peakRuntimeBytes = 0L;

    private RuntimeGlResourceTracker() {
    }

    public static void afterTexImage2D(final int target,
                                       final int level,
                                       final int internalFormat,
                                       final int width,
                                       final int height,
                                       final int border,
                                       final int format,
                                       final int type,
                                       final java.nio.ByteBuffer pixels) {
        if (level != 0 || width <= 0 || height <= 0) {
            return;
        }

        final int textureId = boundTextureId(target);
        if (textureId <= 0) {
            return;
        }

        final OriginInfo origin = detectOrigin();
        final long estimatedBytes = estimateTextureBytes(internalFormat, format, type, width, height, 1);
        TEXTURES.put(textureId, new TextureAllocation(textureId, target, width, height, 1, internalFormat,
                estimatedBytes, origin.category(), origin.owner()));
        updatePeaks();
        maybeEmitSummary(System.nanoTime());
    }

    public static void afterTexImage3D(final int target,
                                       final int level,
                                       final int internalFormat,
                                       final int width,
                                       final int height,
                                       final int depth,
                                       final int border,
                                       final int format,
                                       final int type,
                                       final java.nio.ByteBuffer pixels) {
        if (level != 0 || width <= 0 || height <= 0 || depth <= 0) {
            return;
        }

        final int textureId = boundTextureId(target);
        if (textureId <= 0) {
            return;
        }

        final OriginInfo origin = detectOrigin();
        final long estimatedBytes = estimateTextureBytes(internalFormat, format, type, width, height, depth);
        TEXTURES.put(textureId, new TextureAllocation(textureId, target, width, height, depth, internalFormat,
                estimatedBytes, origin.category(), origin.owner()));
        updatePeaks();
        maybeEmitSummary(System.nanoTime());
    }

    public static void beforeDeleteTexture(final int textureId) {
        if (textureId <= 0) {
            return;
        }
        TEXTURES.remove(textureId);
        maybeEmitSummary(System.nanoTime());
    }

    public static void beforeDeleteTextures(final IntBuffer textureIds) {
        if (textureIds == null) {
            return;
        }

        final IntBuffer copy = textureIds.duplicate();
        while (copy.hasRemaining()) {
            final int textureId = copy.get();
            if (textureId > 0) {
                TEXTURES.remove(textureId);
            }
        }
        maybeEmitSummary(System.nanoTime());
    }

    public static void afterRenderbufferStorage(final int target,
                                                final int internalFormat,
                                                final int width,
                                                final int height) {
        if (width <= 0 || height <= 0) {
            return;
        }

        final int renderbufferId = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        if (renderbufferId <= 0) {
            return;
        }

        final OriginInfo origin = detectOrigin();
        final long estimatedBytes = estimateRenderbufferBytes(internalFormat, width, height);
        RENDERBUFFERS.put(renderbufferId, new RenderbufferAllocation(renderbufferId, width, height, internalFormat,
                estimatedBytes, origin.category(), origin.owner()));
        updatePeaks();
        maybeEmitSummary(System.nanoTime());
    }

    public static void beforeDeleteRenderbuffer(final int renderbufferId) {
        if (renderbufferId <= 0) {
            return;
        }
        RENDERBUFFERS.remove(renderbufferId);
        maybeEmitSummary(System.nanoTime());
    }

    public static void beforeDeleteRenderbuffers(final IntBuffer renderbufferIds) {
        if (renderbufferIds == null) {
            return;
        }

        final IntBuffer copy = renderbufferIds.duplicate();
        while (copy.hasRemaining()) {
            final int renderbufferId = copy.get();
            if (renderbufferId > 0) {
                RENDERBUFFERS.remove(renderbufferId);
            }
        }
        maybeEmitSummary(System.nanoTime());
    }

    static void resetForTest() {
        TEXTURES.clear();
        RENDERBUFFERS.clear();
        nextSummaryNanos = 0L;
        peakLiveBytes = 0L;
        peakRuntimeBytes = 0L;
    }

    static void trackTextureForTest(final int textureId,
                                    final long estimatedBytes,
                                    final boolean fileBacked,
                                    final String owner) {
        TEXTURES.put(textureId, new TextureAllocation(textureId, GL11.GL_TEXTURE_2D, 1, 1, 1, GL_RGBA8,
                estimatedBytes, fileBacked ? ResourceCategory.FILE_BACKED : ResourceCategory.RUNTIME,
                normalizedOwner(fileBacked, owner)));
        updatePeaks();
    }

    static void trackRenderbufferForTest(final int renderbufferId,
                                         final long estimatedBytes,
                                         final boolean fileBacked,
                                         final String owner) {
        RENDERBUFFERS.put(renderbufferId, new RenderbufferAllocation(renderbufferId, 1, 1, GL_DEPTH24_STENCIL8,
                estimatedBytes, fileBacked ? ResourceCategory.FILE_BACKED : ResourceCategory.RUNTIME,
                normalizedOwner(fileBacked, owner)));
        updatePeaks();
    }

    static void removeTextureForTest(final int textureId) {
        TEXTURES.remove(textureId);
    }

    static long summaryLogIntervalMillis() {
        return Long.getLong(SUMMARY_LOG_INTERVAL_MILLIS_PROPERTY, DEFAULT_SUMMARY_LOG_INTERVAL_MILLIS);
    }

    static String formatSummary() {
        final SummarySnapshot snapshot = snapshot();
        return String.format(Locale.ROOT,
                "[SSOptimizer] Runtime GL summary: textures=%d runtimeTextures=%d fileBackedTextures=%d renderbuffers=%d runtimeTextureMiB=%.1f fileBackedTextureMiB=%.1f renderbufferMiB=%.1f liveMiB=%.1f peakLiveMiB=%.1f peakRuntimeMiB=%.1f topRuntimeOwners=%s",
                snapshot.textureCount,
                snapshot.runtimeTextureCount,
                snapshot.fileBackedTextureCount,
                snapshot.renderbufferCount,
                toMiB(snapshot.runtimeTextureBytes),
                toMiB(snapshot.fileBackedTextureBytes),
                toMiB(snapshot.renderbufferBytes),
                toMiB(snapshot.liveBytes),
                toMiB(peakLiveBytes),
                toMiB(peakRuntimeBytes),
                snapshot.topRuntimeOwners);
    }

    private static void maybeEmitSummary(final long now) {
        final long intervalMillis = summaryLogIntervalMillis();
        if (intervalMillis <= 0L) {
            return;
        }

        final long scheduled = nextSummaryNanos;
        if (now < scheduled) {
            return;
        }
        nextSummaryNanos = now + intervalMillis * 1_000_000L;

        if (TEXTURES.isEmpty() && RENDERBUFFERS.isEmpty()) {
            return;
        }
        LOGGER.info(formatSummary());
    }

    private static void updatePeaks() {
        final SummarySnapshot snapshot = snapshot();
        if (snapshot.liveBytes > peakLiveBytes) {
            peakLiveBytes = snapshot.liveBytes;
        }
        if (snapshot.runtimeBytes > peakRuntimeBytes) {
            peakRuntimeBytes = snapshot.runtimeBytes;
        }
    }

    private static SummarySnapshot snapshot() {
        long runtimeTextureBytes = 0L;
        long fileBackedTextureBytes = 0L;
        long renderbufferBytes = 0L;
        int runtimeTextureCount = 0;
        int fileBackedTextureCount = 0;
        final Map<String, Long> runtimeOwners = new HashMap<>();

        for (TextureAllocation texture : TEXTURES.values()) {
            if (texture.category == ResourceCategory.FILE_BACKED) {
                fileBackedTextureBytes += texture.estimatedBytes;
                fileBackedTextureCount++;
            } else {
                runtimeTextureBytes += texture.estimatedBytes;
                runtimeTextureCount++;
                runtimeOwners.merge(texture.owner, texture.estimatedBytes, Long::sum);
            }
        }

        for (RenderbufferAllocation renderbuffer : RENDERBUFFERS.values()) {
            renderbufferBytes += renderbuffer.estimatedBytes;
            if (renderbuffer.category == ResourceCategory.RUNTIME) {
                runtimeOwners.merge(renderbuffer.owner, renderbuffer.estimatedBytes, Long::sum);
            }
        }

        final long runtimeBytes = runtimeTextureBytes + renderbufferBytes;
        return new SummarySnapshot(
                TEXTURES.size(),
                runtimeTextureCount,
                fileBackedTextureCount,
                RENDERBUFFERS.size(),
                runtimeTextureBytes,
                fileBackedTextureBytes,
                renderbufferBytes,
                runtimeBytes + fileBackedTextureBytes,
                runtimeBytes,
                summarizeOwners(runtimeOwners));
    }

    private static String summarizeOwners(final Map<String, Long> owners) {
        if (owners.isEmpty()) {
            return "(none)";
        }

        final Map<String, Long> displayOwners = new HashMap<>();
        for (Map.Entry<String, Long> entry : owners.entrySet()) {
            displayOwners.merge(displayOwner(entry.getKey()), entry.getValue(), Long::sum);
        }

        final List<Map.Entry<String, Long>> sorted = new ArrayList<>(displayOwners.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                              .reversed()
                              .thenComparing(Map.Entry::getKey));

        final StringBuilder out = new StringBuilder();
        final int limit = Math.min(3, sorted.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                out.append(", ");
            }
            final Map.Entry<String, Long> entry = sorted.get(i);
            out.append(entry.getKey())
               .append('=')
               .append(String.format(Locale.ROOT, "%.1fMiB", toMiB(entry.getValue())));
        }
        return out.toString();
    }

    private static String displayOwner(final String owner) {
        if (owner == null || owner.isBlank()) {
            return "unknown";
        }
        if (!owner.contains("#")) {
            return owner;
        }

        final String className = owner.substring(0, owner.indexOf('#'));
        final int lastDot = className.lastIndexOf('.');
        if (lastDot < 0 || lastDot >= className.length() - 1) {
            return className;
        }
        return className.substring(lastDot + 1);
    }

    private static OriginInfo detectOrigin() {
        final StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        boolean fileBacked = false;
        String runtimeOwner = "unknown";

        for (StackTraceElement frame : frames) {
            if (isFileBackedMarker(frame.getClassName())) {
                fileBacked = true;
                break;
            }
        }

        if (!fileBacked) {
            for (StackTraceElement frame : frames) {
                final String className = frame.getClassName();
                if (shouldIgnoreOwnerFrame(className)) {
                    continue;
                }
                runtimeOwner = className;
                break;
            }
        }

        return new OriginInfo(fileBacked ? ResourceCategory.FILE_BACKED : ResourceCategory.RUNTIME,
                normalizedOwner(fileBacked, runtimeOwner));
    }

    private static boolean isFileBackedMarker(final String className) {
        return "github.kasuminova.ssoptimizer.loading.TextureUploadHelper".equals(className)
                || "github.kasuminova.ssoptimizer.loading.LazyTextureManager".equals(className)
                || "com.fs.graphics.TextureLoader".equals(className);
    }

    private static boolean shouldIgnoreOwnerFrame(final String className) {
        return className == null
                || className.equals(RuntimeGlResourceTracker.class.getName())
                || className.startsWith("org.lwjgl.opengl.")
                || className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("github.kasuminova.ssoptimizer.loading.TextureUploadHelper")
                || className.startsWith("github.kasuminova.ssoptimizer.loading.LazyTextureManager");
    }

    private static String normalizedOwner(final boolean fileBacked, final String owner) {
        if (fileBacked) {
            return "managed-file-backed";
        }
        return owner == null || owner.isBlank() ? "unknown" : owner;
    }

    private static int boundTextureId(final int target) {
        final int bindingQuery = switch (target) {
            case GL11.GL_TEXTURE_2D -> GL_TEXTURE_BINDING_2D;
            case GL12.GL_TEXTURE_3D -> GL_TEXTURE_BINDING_3D;
            default -> isCubeMapFace(target) || target == GL_TEXTURE_CUBE_MAP ? GL_TEXTURE_BINDING_CUBE_MAP : 0;
        };
        return bindingQuery == 0 ? -1 : GL11.glGetInteger(bindingQuery);
    }

    private static boolean isCubeMapFace(final int target) {
        return target >= GL_TEXTURE_CUBE_MAP_POSITIVE_X && target <= GL_TEXTURE_CUBE_MAP_NEGATIVE_Z;
    }

    private static long estimateTextureBytes(final int internalFormat,
                                             final int format,
                                             final int type,
                                             final int width,
                                             final int height,
                                             final int depth) {
        final int bytesPerPixel = Math.max(1, bytesPerPixel(internalFormat, format, type));
        return (long) Math.max(width, 0)
                * Math.max(height, 0)
                * Math.max(depth, 0)
                * bytesPerPixel;
    }

    private static long estimateRenderbufferBytes(final int internalFormat,
                                                  final int width,
                                                  final int height) {
        return (long) Math.max(width, 0)
                * Math.max(height, 0)
                * Math.max(1, bytesPerPixel(internalFormat, internalFormat, GL_UNSIGNED_BYTE));
    }

    private static int bytesPerPixel(final int internalFormat,
                                     final int format,
                                     final int type) {
        return switch (internalFormat) {
            case GL_R8, GL_STENCIL_INDEX8 -> 1;
            case GL_R16, GL_R16F, GL_DEPTH_COMPONENT16 -> 2;
            case GL_RG8 -> 2;
            case GL_RG16, GL_RG16F -> 4;
            case GL_R32F, GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT32, GL_DEPTH_COMPONENT32F -> 4;
            case GL_RGB8, GL_RGB10, GL_RGB12 -> 3;
            case GL_RGB16, GL_RGB16F -> 6;
            case GL_RGB32F -> 12;
            case GL_RGBA8, GL_RGB10_A2, GL_RGBA12, GL_SRGB8_ALPHA8, GL_DEPTH24_STENCIL8 -> 4;
            case GL_RGBA16, GL_RGBA16F, GL_RG32F, GL_DEPTH32F_STENCIL8 -> 8;
            case GL_RGBA32F -> 16;
            case GL_RED -> bytesPerChannel(type);
            case GL_RG -> 2 * bytesPerChannel(type);
            case GL_RGB -> 3 * bytesPerChannel(type);
            case GL_RGBA -> 4 * bytesPerChannel(type);
            case GL_DEPTH_COMPONENT -> Math.max(2, bytesPerChannel(type));
            case GL_DEPTH_STENCIL -> 4;
            case GL_STENCIL_INDEX -> 1;
            default -> Math.max(1, channelCount(format) * bytesPerChannel(type));
        };
    }

    private static int channelCount(final int format) {
        return switch (format) {
            case GL_RED, GL_DEPTH_COMPONENT, GL_STENCIL_INDEX -> 1;
            case GL_RG, GL_DEPTH_STENCIL -> 2;
            case GL_RGB -> 3;
            case GL_RGBA -> 4;
            default -> 4;
        };
    }

    private static int bytesPerChannel(final int type) {
        return switch (type) {
            case GL_UNSIGNED_BYTE, GL_BYTE -> 1;
            case GL_UNSIGNED_SHORT, GL_SHORT, GL_HALF_FLOAT -> 2;
            case GL_UNSIGNED_INT, GL_INT, GL_FLOAT -> 4;
            default -> 1;
        };
    }

    private static double toMiB(final long bytes) {
        return bytes / 1_048_576.0;
    }

    private enum ResourceCategory {
        FILE_BACKED,
        RUNTIME
    }

    private record OriginInfo(ResourceCategory category,
                              String owner) {
    }

    private record TextureAllocation(int textureId,
                                     int target,
                                     int width,
                                     int height,
                                     int depth,
                                     int internalFormat,
                                     long estimatedBytes,
                                     ResourceCategory category,
                                     String owner) {
    }

    private record RenderbufferAllocation(int renderbufferId,
                                          int width,
                                          int height,
                                          int internalFormat,
                                          long estimatedBytes,
                                          ResourceCategory category,
                                          String owner) {
    }

    private record SummarySnapshot(int textureCount,
                                   int runtimeTextureCount,
                                   int fileBackedTextureCount,
                                   int renderbufferCount,
                                   long runtimeTextureBytes,
                                   long fileBackedTextureBytes,
                                   long renderbufferBytes,
                                   long liveBytes,
                                   long runtimeBytes,
                                   String topRuntimeOwners) {
    }
}
