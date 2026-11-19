package github.kasuminova.ssoptimizer.common.render.campaign;

import java.util.Arrays;
import java.util.BitSet;

public final class CampaignTrailPairs implements CampaignArrayMesh {
    private static final int STRIDE = 6;
    private float[] points = new float[32 * STRIDE];
    private int[] colors = new int[32];
    private final BitSet joins = new BitSet();
    private int pairs;

    int size() { return pairs; }

    void add(float x, float y, float nx, float ny, float halfWidth, float u, int rgba) {
        ensureCapacity();
        int p = pairs * STRIDE;
        points[p] = x;
        points[p + 1] = y;
        points[p + 2] = nx;
        points[p + 3] = ny;
        points[p + 4] = halfWidth;
        points[p + 5] = u;
        colors[pairs++] = rgba;
    }

    void join() {
        ensureCapacity();
        joins.set(pairs++);
    }

    private void ensureCapacity() {
        if (pairs == colors.length) {
            colors = Arrays.copyOf(colors, pairs * 2);
            points = Arrays.copyOf(points, pairs * 2 * STRIDE);
        }
    }

    public int mode() { return org.lwjgl.opengl.GL11.GL_QUAD_STRIP; }

    public int vertexCount() { return pairs * 2; }

    public void expand(Vertex out) {
        float rightX = 0, rightY = 0, u = 0;
        int rgba = 0;
        for (int i = 0; i < pairs; i++) {
            if (joins.get(i)) {
                out.put(rightX, rightY, u, 0.99F, rgba);
            } else {
                int p = i * STRIDE;
                float x = points[p], y = points[p + 1];
                float dx = points[p + 2] * points[p + 4], dy = points[p + 3] * points[p + 4];
                u = points[p + 5];
                rgba = colors[i];
                out.put(x - dx, y - dy, u, 0.01F, rgba);
                rightX = x + dx;
                rightY = y + dy;
            }
            out.put(rightX, rightY, u, 0.99F, rgba);
        }
    }
}
