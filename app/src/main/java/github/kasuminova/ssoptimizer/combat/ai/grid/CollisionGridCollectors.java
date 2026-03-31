package github.kasuminova.ssoptimizer.combat.ai.grid;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.*;

final class CollisionGridCollectors {
    private CollisionGridCollectors() {
    }

    static Iterator<Object> collectDistinctOrdered(final List<Object>[][] cells,
                                                   final int minX,
                                                   final int maxX,
                                                   final int minY,
                                                   final int maxY) {
        final int estimatedEntries = estimateEntries(cells, minX, maxX, minY, maxY);
        if (estimatedEntries <= 0) {
            return Collections.emptyIterator();
        }

        final ObjectArrayList<Object> ordered = new ObjectArrayList<>(estimatedEntries);
        final ObjectOpenHashSet<Object> seen = new ObjectOpenHashSet<>(hashCapacityFor(estimatedEntries));
        populateFastutilSetAndArrayList(cells, minX, maxX, minY, maxY, seen, ordered);
        return ordered.isEmpty() ? Collections.emptyIterator() : ordered.iterator();
    }

    static Iterator<Object> collectDistinctOrderedJdk(final List<Object>[][] cells,
                                                      final int minX,
                                                      final int maxX,
                                                      final int minY,
                                                      final int maxY) {
        final int estimatedEntries = estimateEntries(cells, minX, maxX, minY, maxY);
        if (estimatedEntries <= 0) {
            return Collections.emptyIterator();
        }

        final ArrayList<Object> ordered = new ArrayList<>(estimatedEntries);
        final HashSet<Object> seen = new HashSet<>(hashCapacityFor(estimatedEntries));
        populateHashSetAndArrayList(cells, minX, maxX, minY, maxY, seen, ordered);
        return ordered.isEmpty() ? Collections.emptyIterator() : ordered.iterator();
    }

    static int linkedHashSetAddAllChecksum(final List<Object>[][] cells,
                                           final int minX,
                                           final int maxX,
                                           final int minY,
                                           final int maxY) {
        final LinkedHashSet<Object> ordered = new LinkedHashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                final List<Object> cell = cells[x][y];
                if (cell == null || cell.isEmpty()) {
                    continue;
                }
                ordered.addAll(cell);
            }
        }
        return checksum(ordered.iterator());
    }

    static int manualLinkedHashSetChecksum(final List<Object>[][] cells,
                                           final int minX,
                                           final int maxX,
                                           final int minY,
                                           final int maxY) {
        final int estimatedEntries = estimateEntries(cells, minX, maxX, minY, maxY);
        final LinkedHashSet<Object> ordered = new LinkedHashSet<>(hashCapacityFor(estimatedEntries));
        forEachCandidate(cells, minX, maxX, minY, maxY, ordered::add);
        return checksum(ordered.iterator());
    }

    static int hashSetAndArrayListChecksum(final List<Object>[][] cells,
                                           final int minX,
                                           final int maxX,
                                           final int minY,
                                           final int maxY) {
        final int estimatedEntries = estimateEntries(cells, minX, maxX, minY, maxY);
        final ArrayList<Object> ordered = new ArrayList<>(estimatedEntries);
        final HashSet<Object> seen = new HashSet<>(hashCapacityFor(estimatedEntries));
        populateHashSetAndArrayList(cells, minX, maxX, minY, maxY, seen, ordered);
        return checksum(ordered.iterator());
    }

    static int fastutilOpenHashSetAndArrayListChecksum(final List<Object>[][] cells,
                                                       final int minX,
                                                       final int maxX,
                                                       final int minY,
                                                       final int maxY) {
        final int estimatedEntries = estimateEntries(cells, minX, maxX, minY, maxY);
        final ObjectArrayList<Object> ordered = new ObjectArrayList<>(estimatedEntries);
        final ObjectOpenHashSet<Object> seen = new ObjectOpenHashSet<>(hashCapacityFor(estimatedEntries));
        populateFastutilSetAndArrayList(cells, minX, maxX, minY, maxY, seen, ordered);
        return checksum(ordered.iterator());
    }

    static int fastutilLinkedOpenHashSetChecksum(final List<Object>[][] cells,
                                                 final int minX,
                                                 final int maxX,
                                                 final int minY,
                                                 final int maxY) {
        final int estimatedEntries = estimateEntries(cells, minX, maxX, minY, maxY);
        final ObjectLinkedOpenHashSet<Object> ordered = new ObjectLinkedOpenHashSet<>(hashCapacityFor(estimatedEntries));
        forEachCandidate(cells, minX, maxX, minY, maxY, ordered::add);
        return checksum(ordered.iterator());
    }

    static int helperIteratorChecksum(final List<Object>[][] cells,
                                      final int gridWidth,
                                      final int gridHeight,
                                      final int baseX,
                                      final int baseY,
                                      final float cellSize,
                                      final float centerX,
                                      final float centerY,
                                      final float width,
                                      final float height) {
        return checksum(CollisionGridQueryHelper.getCheckIterator(
                cells,
                gridWidth,
                gridHeight,
                baseX,
                baseY,
                cellSize,
                centerX,
                centerY,
                width,
                height));
    }

    private static int estimateEntries(final List<Object>[][] cells,
                                       final int minX,
                                       final int maxX,
                                       final int minY,
                                       final int maxY) {
        int estimatedEntries = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                final List<Object> cell = cells[x][y];
                if (cell != null) {
                    estimatedEntries += cell.size();
                }
            }
        }
        return estimatedEntries;
    }

    private static int hashCapacityFor(final int expectedEntries) {
        final int safeExpected = Math.max(16, expectedEntries);
        return (int) ((safeExpected / 0.75f) + 1.0f);
    }

    private static void populateHashSetAndArrayList(final List<Object>[][] cells,
                                                    final int minX,
                                                    final int maxX,
                                                    final int minY,
                                                    final int maxY,
                                                    final HashSet<Object> seen,
                                                    final ArrayList<Object> ordered) {
        forEachCandidate(cells, minX, maxX, minY, maxY, candidate -> {
            if (seen.add(candidate)) {
                ordered.add(candidate);
            }
        });
    }

    private static void populateFastutilSetAndArrayList(final List<Object>[][] cells,
                                                        final int minX,
                                                        final int maxX,
                                                        final int minY,
                                                        final int maxY,
                                                        final ObjectOpenHashSet<Object> seen,
                                                        final ObjectArrayList<Object> ordered) {
        forEachCandidate(cells, minX, maxX, minY, maxY, candidate -> {
            if (seen.add(candidate)) {
                ordered.add(candidate);
            }
        });
    }

    private static void forEachCandidate(final List<Object>[][] cells,
                                         final int minX,
                                         final int maxX,
                                         final int minY,
                                         final int maxY,
                                         final CandidateConsumer consumer) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                final List<Object> cell = cells[x][y];
                if (cell == null || cell.isEmpty()) {
                    continue;
                }

                if (cell instanceof RandomAccess) {
                    for (int i = 0, size = cell.size(); i < size; i++) {
                        consumer.accept(cell.get(i));
                    }
                } else {
                    for (Object candidate : cell) {
                        consumer.accept(candidate);
                    }
                }
            }
        }
    }

    private static int checksum(final Iterator<Object> iterator) {
        int checksum = 1;
        while (iterator.hasNext()) {
            checksum = 31 * checksum + iterator.next().hashCode();
        }
        return checksum;
    }

    @FunctionalInterface
    private interface CandidateConsumer {
        void accept(Object candidate);
    }
}