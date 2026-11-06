package github.kasuminova.ssoptimizer.common.render.spritebatch;

import java.nio.ByteBuffer;

public final class SpriteQuadPacker {
    private static final float DEG_TO_RAD = 0.017453292519943295769f;

    public static final int VERTEX_BYTES = 20;
    public static final int MAX_QUADS_PER_DRAW = 16383;

    private SpriteQuadPacker() {
    }

    public static void packQuad(ByteBuffer verts, ByteBuffer indices, int baseVertex,
                                float[] mv,
                                float posX, float posY, float width, float height,
                                float centerX, float centerY, float angle,
                                int r, int g, int b, int a,
                                float texX, float texY, float texWidth, float texHeight) {
        float cx = (centerX != -1.0f && centerY != -1.0f) ? centerX : width * 0.5f;
        float cy = (centerX != -1.0f && centerY != -1.0f) ? centerY : height * 0.5f;
        float originX = posX + width * 0.5f;
        float originY = posY + height * 0.5f;

        float sinA = 0.0f;
        float cosA = 1.0f;
        if (angle != 0.0f) {
            float radians = angle * DEG_TO_RAD;
            sinA = (float) Math.sin(radians);
            cosA = (float) Math.cos(radians);
        }

        float m0 = mv[0], m1 = mv[1], m4 = mv[4], m5 = mv[5], m12 = mv[12], m13 = mv[13];
        float[] xy = new float[2];
        transform(xy, originX, originY, -cx, -cy, sinA, cosA, m0, m1, m4, m5, m12, m13);
        putVertex(verts, xy, texX, texY, r, g, b, a);
        transform(xy, originX, originY, -cx, height - cy, sinA, cosA, m0, m1, m4, m5, m12, m13);
        putVertex(verts, xy, texX, texY + texHeight, r, g, b, a);
        transform(xy, originX, originY, width - cx, height - cy, sinA, cosA, m0, m1, m4, m5, m12, m13);
        putVertex(verts, xy, texX + texWidth, texY + texHeight, r, g, b, a);
        transform(xy, originX, originY, width - cx, -cy, sinA, cosA, m0, m1, m4, m5, m12, m13);
        putVertex(verts, xy, texX + texWidth, texY, r, g, b, a);

        indices.putShort((short) baseVertex);
        indices.putShort((short) (baseVertex + 1));
        indices.putShort((short) (baseVertex + 2));
        indices.putShort((short) baseVertex);
        indices.putShort((short) (baseVertex + 2));
        indices.putShort((short) (baseVertex + 3));
    }

    private static void transform(float[] out, float originX, float originY, float localX, float localY,
                                  float sinA, float cosA,
                                  float m0, float m1, float m4, float m5, float m12, float m13) {
        float x = originX + localX * cosA - localY * sinA;
        float y = originY + localX * sinA + localY * cosA;
        out[0] = m0 * x + m4 * y + m12;
        out[1] = m1 * x + m5 * y + m13;
    }

    private static void putVertex(ByteBuffer out, float[] xy, float u, float v,
                                  int r, int g, int b, int a) {
        out.putFloat(xy[0]);
        out.putFloat(xy[1]);
        out.putFloat(u);
        out.putFloat(v);
        out.put((byte) r);
        out.put((byte) g);
        out.put((byte) b);
        out.put((byte) a);
    }
}
