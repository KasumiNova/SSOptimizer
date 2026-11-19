package github.kasuminova.ssoptimizer.common.render.campaign;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CampaignTrailPairsTest {
    private record Vertex(float x, float y, float u, float v, int color) {}

    @Test
    void expandedPointsMatchImmediatePairsAndRepeatedRightEndpoints() {
        Random random = new Random(0x73a10);
        var batch = new CampaignTrailPairs();
        var expected = new ArrayList<Vertex>();
        for (int i = 0; i < 180; i++) {
            if (i > 0 && i % 5 == 0) {
                Vertex last = expected.getLast();
                expected.addAll(List.of(last, last));
                batch.join();
            }
            float x = random.nextFloat(), y = random.nextFloat();
            float nx = random.nextFloat() - 0.5f, ny = random.nextFloat() - 0.5f;
            float half = random.nextFloat() * 14, u = i / 97f;
            int color = random.nextInt();
            expected.add(new Vertex(x - nx * half, y - ny * half, u, 0.01f, color));
            expected.add(new Vertex(x + nx * half, y + ny * half, u, 0.99f, color));
            batch.add(x, y, nx, ny, half, u, color);
        }
        var actual = new ArrayList<Vertex>();
        batch.expand((x, y, u, v, color) -> actual.add(new Vertex(x, y, u, v, color)));
        assertEquals(expected.size(), batch.vertexCount());
        assertEquals(expected, actual);
    }
}
