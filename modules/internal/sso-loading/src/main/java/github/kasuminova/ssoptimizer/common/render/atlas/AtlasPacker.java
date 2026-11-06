package github.kasuminova.ssoptimizer.common.render.atlas;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AtlasPacker {

    public record Entry(String path, int width, int height, String affinity) {
        public Entry(final String path, final int width, final int height) {
            this(path, width, height, "");
        }
    }

    public record Placement(String path, int page, int x, int y, int width, int height) {
    }

    public record Page(int index, List<Placement> placements) {
    }

    public record Result(List<Page> pages, List<Entry> skipped) {
    }

    private AtlasPacker() {
    }

    public static Result pack(final List<Entry> entries, final int atlasSize, final int padding) {
        final Map<String, Integer> groupMaxHeight = new HashMap<>();
        for (Entry entry : entries) {
            groupMaxHeight.merge(entry.affinity(), entry.height(), Math::max);
        }
        final List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator
                .comparingInt((Entry e) -> groupMaxHeight.get(e.affinity())).reversed()
                .thenComparing(Entry::affinity)
                .thenComparing(Comparator.comparingInt(Entry::height).reversed())
                .thenComparing(Entry::path));

        final List<Page> pages = new ArrayList<>();
        final List<Entry> skipped = new ArrayList<>();
        List<Placement> currentPlacements = new ArrayList<>();
        int cursorX = 0;
        int cursorY = 0;
        int shelfHeight = 0;

        for (Entry entry : sorted) {
            final int cellWidth = entry.width() + padding * 2;
            final int cellHeight = entry.height() + padding * 2;
            if (cellWidth > atlasSize || cellHeight > atlasSize) {
                skipped.add(entry);
                continue;
            }
            if (cursorX + cellWidth > atlasSize) {
                cursorY += shelfHeight;
                cursorX = 0;
                shelfHeight = 0;
            }
            if (cursorY + cellHeight > atlasSize) {
                pages.add(new Page(pages.size(), currentPlacements));
                currentPlacements = new ArrayList<>();
                cursorX = 0;
                cursorY = 0;
                shelfHeight = 0;
            }
            currentPlacements.add(new Placement(
                    entry.path(), pages.size(), cursorX + padding, cursorY + padding,
                    entry.width(), entry.height()));
            cursorX += cellWidth;
            shelfHeight = Math.max(shelfHeight, cellHeight);
        }
        if (!currentPlacements.isEmpty()) {
            pages.add(new Page(pages.size(), currentPlacements));
        }
        return new Result(pages, skipped);
    }
}
