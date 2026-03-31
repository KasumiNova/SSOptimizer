package github.kasuminova.ssoptimizer.combat.ai.grid;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CollisionGridQueryHelperTest {
    @Test
    void deduplicatesAcrossCellsWhilePreservingFirstOccurrenceOrder() {
        List<Object>[][] cells = createGrid(2, 2);
        Probe alpha1 = new Probe("alpha");
        Probe alpha2 = new Probe("alpha");
        Probe beta = new Probe("beta");
        Probe gamma = new Probe("gamma");

        cells[0][0] = new ArrayList<>(List.of(alpha1, beta));
        cells[0][1] = new ArrayList<>(List.of(alpha2, gamma));

        Iterator<Object> iterator = CollisionGridQueryHelper.getCheckIterator(
                cells, 2, 2, 0, 0, 1.0f,
                0.5f, 0.5f, 2.0f, 2.0f
        );

        List<Object> result = drain(iterator);
        assertEquals(List.of(alpha1, beta, gamma), result);
    }

    @Test
    void clampsQueryBoundsToGridEdges() {
        List<Object>[][] cells = createGrid(3, 3);
        Probe edge = new Probe("edge");
        Probe center = new Probe("center");
        cells[0][0] = new ArrayList<>(List.of(edge));
        cells[1][1] = new ArrayList<>(List.of(center));

        Iterator<Object> iterator = CollisionGridQueryHelper.getCheckIterator(
                cells, 3, 3, 0, 0, 10.0f,
                -50.0f, -50.0f, 200.0f, 200.0f
        );

        List<Object> result = drain(iterator);
        assertEquals(List.of(edge, center), result);
    }

    @Test
    void returnsEmptyIteratorWhenNoCellsMatch() {
        List<Object>[][] cells = createGrid(2, 2);

        Iterator<Object> iterator = CollisionGridQueryHelper.getCheckIterator(
                cells, 2, 2, 0, 0, 1.0f,
                100.0f, 100.0f, 1.0f, 1.0f
        );

        assertFalse(iterator.hasNext());
    }

    @SuppressWarnings("unchecked")
    private List<Object>[][] createGrid(int width, int height) {
        return (List<Object>[][]) new List[width][height];
    }

    private List<Object> drain(Iterator<Object> iterator) {
        List<Object> result = new ArrayList<>();
        while (iterator.hasNext()) {
            result.add(iterator.next());
        }
        return result;
    }

    private record Probe(String id) {
    }
}