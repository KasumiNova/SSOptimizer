package github.kasuminova.ssoptimizer.common.font;

import github.kasuminova.ssoptimizer.bridge.opengl.FontAtlasGl;
import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import github.kasuminova.ssoptimizer.common.font.layout.GlyphMetrics;
import org.apache.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class FloatingGlyphAtlas {
    private static final Logger LOG = Logger.getLogger(FloatingGlyphAtlas.class);
    private static final int SIZE = 1024, MAX_PAGES = 4, MAX_READY = 8192, MAX_JOBS = 48;
    private static final int MAX_DEMAND = 2048;
    private static final boolean DIAGNOSTICS = Boolean.getBoolean("ssoptimizer.font.floating.stats");
    private static final class Holder { static final FloatingGlyphAtlas ATLAS = new FloatingGlyphAtlas(); }
    static FloatingGlyphAtlas shared() { return Holder.ATLAS; }

    record Shape(TtfGlyphProvider font, int cp, float stroke) {}
    record Key(Shape shape, int bucket) {}
    record Choice(GlyphMetrics glyph, boolean original) {}
    private static final Choice COLD = new Choice(null, false), ORIGINAL = new Choice(null, true);
    private record Ticket(Key key, GlyphMetrics base, int epoch) {}
    private record Cell(Page page, int x, int y) {}

    private static final class Page {
        int x, y, row, texture;

        Cell reserve(int w, int h) {
            int nx = x, ny = y, nr = row;
            if (nx + w > SIZE) { nx = 0; ny += nr; nr = 0; }
            if (ny + h > SIZE) return null;
            x = nx + w;
            y = ny;
            row = Math.max(nr, h);
            return new Cell(this, nx, ny);
        }
    }

    private final Map<Shape, NavigableMap<Integer, Choice>> ready = new HashMap<>();
    private final HashSet<Key> pending = new HashSet<>();
    private final FloatingGlyphDemand<Key> demand = new FloatingGlyphDemand<>(MAX_DEMAND);
    private final ArrayList<Page> pages = new ArrayList<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_JOBS), Thread.ofVirtual().name("SSOptimizer-FloatingGlyph").factory());
    private int epoch, entries;
    private long exact, adjacent, cold, original, queued, uploaded, rejected, lastLog;

    private FloatingGlyphAtlas() {
        GlDispatch.registerContextRecreatedListener(this::invalidate);
    }

    private synchronized void invalidate() {
        epoch++;
        pending.clear();
        ready.clear();
        demand.invalidate();
        pages.clear();
        entries = 0;
    }

    synchronized int epoch() { return epoch; }

    synchronized Choice lookup(Shape shape, int bucket, GlyphMetrics base) {
        var sizes = ready.get(shape);
        var best = sizes == null ? null : FloatingGlyphDemand.nearest(sizes, bucket);
        Choice selected;
        if (best != null && best.getKey() == bucket) {
            exact++;
            selected = best.getValue();
        } else if (request(new Key(shape, bucket), base, best == null) == FloatingGlyphDemand.Action.ORIGINAL) {
            original++;
            selected = ORIGINAL;
        } else {
            if (best == null) cold++; else adjacent++;
            selected = best == null ? COLD : best.getValue();
        }
        if (DIAGNOSTICS && ((cold + exact + adjacent + original) & 255) == 0 && System.nanoTime() - lastLog > 5_000_000_000L) {
            lastLog = System.nanoTime();
            LOG.info(summary());
        }
        return selected;
    }

    synchronized void prewarm(Shape shape, int bucket, GlyphMetrics base) {
        var sizes = ready.get(shape);
        if (sizes == null || !sizes.containsKey(bucket)) request(new Key(shape, bucket), base, true);
    }

    private FloatingGlyphDemand.Action request(Key key, GlyphMetrics base, boolean immediate) {
        var action = demand.plan(key, immediate, pending.contains(key), pending.size() >= MAX_JOBS);
        if (action != FloatingGlyphDemand.Action.ENQUEUE) return action;
        pending.add(key);
        Ticket ticket = new Ticket(key, base, epoch);
        try {
            worker.execute(() -> rasterize(ticket));
            queued++;
        } catch (RejectedExecutionException ex) {
            pending.remove(key);
            rejected++;
            return FloatingGlyphDemand.Action.WAIT;
        }
        return action;
    }

    private void rasterize(Ticket job) {
        try {
            synchronized (this) {
                if (epoch != job.epoch() || demand.full()) { finish(job, false); return; }
            }
            Key key = job.key();
            NativeGlyphBitmap ink = key.shape().font().floatingBitmap(key.shape().cp(), key.bucket(), key.shape().stroke(), job.base());
            if (ink == null || !ink.hasImage() || ink.width() > SIZE - 2 || ink.height() > SIZE - 2) {
                finish(job, true);
                return;
            }
            ByteBuffer payload = paddedAlpha(ink);
            int w = ink.width(), h = ink.height();
            GlDispatch.submit(() -> upload(job, w, h, payload));
        } catch (RuntimeException ex) {
            finish(job, true);
            LOG.warn("飘字字形准备失败: " + job.key(), ex);
        }
    }

    static ByteBuffer paddedAlpha(NativeGlyphBitmap ink) {
        int stride = ink.width() + 2;
        ByteBuffer data = ByteBuffer.allocateDirect(stride * (ink.height() + 2));
        int[] pixels = ink.argbPixels();
        for (int y = 0; y < ink.height(); y++) {
            int start = (ink.height() - y) * stride + 1;
            for (int x = 0; x < ink.width(); x++) data.put(start + x, (byte)(pixels[y * ink.width() + x] >>> 24));
        }
        return data.asReadOnlyBuffer();
    }

    private Cell reserve(int w, int h) {
        for (var page : pages) {
            var cell = page.reserve(w, h);
            if (cell != null) return cell;
        }
        if (pages.size() == MAX_PAGES) return null;
        var page = new Page();
        pages.add(page);
        return page.reserve(w, h);
    }

    private void upload(Ticket job, int w, int h, ByteBuffer payload) {
        boolean failed = true;
        try {
            Cell cell;
            synchronized (this) {
                if (job.epoch() != epoch || entries >= MAX_READY) return;
                cell = reserve(w + 2, h + 2);
                if (cell == null) demand.filled();
            }
            if (cell == null) return;
            Page page = cell.page();
            if (page.texture == 0) {
                page.texture = FontAtlasGl.createAlphaTexture(SIZE);
                if (page.texture == 0) throw new IllegalStateException("飘字纹理创建返回 0");
            }
            FontAtlasGl.uploadAlphaRect(page.texture, SIZE, cell.x(), cell.y(), w + 2, h + 2, payload);
            Key key = job.key();
            GlyphMetrics glyph = geometry(job.base(), key.shape().stroke(), (cell.x() + 1f) / SIZE,
                    (cell.y() + 1f) / SIZE, (float) w / SIZE, (float) h / SIZE, page.texture);
            synchronized (this) {
                if (job.epoch() != epoch) return;
                ready.computeIfAbsent(key.shape(), k -> new TreeMap<>()).put(key.bucket(), new Choice(glyph,false));
                entries++;
                if (entries == MAX_READY) demand.filled();
                uploaded++;
            }
            failed = false;
        } catch (RuntimeException ex) {
            LOG.warn("飘字纹理上传失败: " + job.key(), ex);
        } finally {
            finish(job, failed);
        }
    }

    private synchronized void finish(Ticket job, boolean failed) {
        if (job.epoch() == epoch) {
            pending.remove(job.key());
            if (failed) { rejected++; demand.failed(job.key()); }
        }
    }

    static GlyphMetrics geometry(GlyphMetrics base, float stroke, float u, float v, float uw, float vh, int texture) {
        return new GlyphMetrics(base.xOffset() - stroke, base.xAdvance() + stroke, base.bearingY() - stroke,
                base.width() + 2 * stroke, base.height() + 2 * stroke, u, v, uw, vh, texture);
    }

    synchronized String summary() {
        return "[SSOptimizer] Floating glyphs: exact=" + exact + " nearest=" + adjacent + " cold=" + cold
                + " original=" + original + " queued=" + queued + " uploaded=" + uploaded + " rejected=" + rejected
                + " pending=" + pending.size() + " entries=" + entries + " pages=" + pages.size() + " full=" + demand.full();
    }
}
