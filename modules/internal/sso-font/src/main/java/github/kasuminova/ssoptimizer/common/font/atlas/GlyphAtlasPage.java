package github.kasuminova.ssoptimizer.common.font.atlas;

import github.kasuminova.ssoptimizer.common.font.NativeGlyphBitmap;
import github.kasuminova.ssoptimizer.bridge.opengl.FontAtlasGl;
import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public final class GlyphAtlasPage {

    private final int        size;
    private ByteBuffer staging;
    private AtomicInteger queuedReaders = new AtomicInteger();
    private final List<int[]> dirtyRects = new ArrayList<>();

    private int shelfY;
    private int shelfX;
    private int shelfHeight;

    private volatile int textureId;
    private final BooleanSupplier directOwner;

    public GlyphAtlasPage(final int size) {
        this(size, null);
    }

    GlyphAtlasPage(final int size, final BooleanSupplier directOwner) {
        this.directOwner = directOwner;
        if (size <= 0) {
            throw new IllegalArgumentException("图集页尺寸必须为正: " + size);
        }
        this.size = size;
        this.staging = ByteBuffer.allocateDirect(size * size);
    }

    public int size() {
        return size;
    }

    public int textureId() {
        return textureId;
    }

    synchronized byte[] stagingSnapshot() {
        final ByteBuffer view = staging.duplicate();
        view.clear();
        final byte[] snapshot = new byte[view.capacity()];
        view.get(snapshot);
        return snapshot;
    }

    public synchronized int[] allocateCell(final int width, final int height) {
        if (width <= 0 || height <= 0 || width > size || height > size) {
            return null;
        }
        if (shelfX + width > size) {
            shelfY += shelfHeight + 1;
            shelfX = 0;
            shelfHeight = 0;
        }
        if (shelfY + height > size) {
            return null;
        }
        final int[] cell = {shelfX, shelfY};
        shelfX += width + 1;
        shelfHeight = Math.max(shelfHeight, height);
        return cell;
    }

    /** staging 使用 GL 行序，写入时必须垂直翻转以匹配 quad 的 UV 约定。 */
    public synchronized void writeBitmap(final int x, final int y, final NativeGlyphBitmap bitmap) {
        if (canAllocate()) {
            ensureTexture();
        }
        final int w = bitmap.width();
        final int h = bitmap.height();
        final int[] argb = bitmap.argbPixels();
        final byte[] rowBuffer = new byte[w];
        for (int row = 0; row < h; row++) {
            final int srcRow = row * w;
            for (int col = 0; col < w; col++) {
                rowBuffer[col] = (byte) (argb[srcRow + col] >>> 24);
            }
            staging.put((y + h - 1 - row) * size + x, rowBuffer, 0, w);
        }
        dirtyRects.add(new int[]{x, y, w, h});
    }

    boolean canAllocate() {
        return directOwner == null ? GlDispatch.isMainRecordingThread() : directOwner.getAsBoolean();
    }

    public synchronized void flushDirty() {
        if (!canAllocate() && (directOwner != null || !GlDispatch.isRenderThread())) {
            return;
        }
        ensureTexture();
        if (dirtyRects.isEmpty()) {
            return;
        }

        final int texture = textureId;
        final List<int[]> rects = new ArrayList<>(dirtyRects);
        dirtyRects.clear();
        final List<ByteBuffer> payloads = new ArrayList<>(rects.size());
        long bytes = 0;
        for (final int[] rect : rects) {
            final ByteBuffer view = staging.duplicate();
            view.position(rect[1] * size + rect[0]);
            view.limit(view.capacity());
            payloads.add(view);
            bytes += (long) rect[2] * rect[3];
        }
        FontAtlasDiagnostics.recordUpload(bytes, rects.size());
        if (directOwner != null) {
            FontAtlasGl.uploadAlphaRects(texture, size, rects, payloads);
        } else {
            final AtomicInteger readers = queuedReaders;
            readers.incrementAndGet();
            try {
                GlDispatch.submit(() -> {
                    try { FontAtlasGl.uploadAlphaRects(texture, size, rects, payloads); }
                    finally { readers.decrementAndGet(); }
                });
            } catch (RuntimeException ex) {
                readers.decrementAndGet();
                throw ex;
            }
        }
    }

    public synchronized void onContextRecreated() {
        textureId = 0;
        dirtyRects.clear();
        dirtyRects.add(new int[]{0, 0, size, size});
    }

    public synchronized void release() {
        final int texture = textureId;
        textureId = 0;
        dirtyRects.clear();
        if (texture != 0) {
            if (directOwner != null) {
                if (!canAllocate()) throw new IllegalStateException("字体纹理释放时当前线程没有 GL 上下文");
                FontAtlasGl.deleteTexture(texture);
            } else {
                GlDispatch.submit(() -> FontAtlasGl.deleteTexture(texture));
            }
        }
    }

    /** 复用前必须清零 staging，避免旧像素经整页回传残留到 cell 间隙并产生采样渗色。 */
    synchronized void resetForReuse() {
        shelfY = 0;
        shelfX = 0;
        shelfHeight = 0;
        dirtyRects.clear();
        if (queuedReaders.get() != 0) {
            staging = ByteBuffer.allocateDirect(size * size);
            queuedReaders = new AtomicInteger();
            return;
        }
        final ByteBuffer view = staging.duplicate();
        view.clear();
        final byte[] zeros = new byte[8192];
        while (view.hasRemaining()) {
            view.put(zeros, 0, Math.min(zeros.length, view.remaining()));
        }
    }

    /** 仅主录制线程或渲染线程可调用，避免 allocate 切割其他线程正在录制的帧。 */
    public synchronized void ensureTexture() {
        if (textureId != 0) {
            return;
        }
        if (directOwner != null && !canAllocate()) throw new IllegalStateException("字体纹理创建时当前线程没有 GL 上下文");
        textureId = directOwner == null ? GlDispatch.allocate(this::createTexture) : createTexture();
        dirtyRects.clear();
        dirtyRects.add(new int[]{0, 0, size, size});
    }

    private int createTexture() {
        return FontAtlasGl.createAlphaTexture(size);
    }
}
