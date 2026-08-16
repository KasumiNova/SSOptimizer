package github.kasuminova.ssoptimizer.common.render.spritebatch;

import java.nio.ByteBuffer;

/**
 * Sprite quad 的 CPU 顶点打包器（纯逻辑，不触碰 GL）。
 * <p>
 * 顶点几何公式与 {@code SpriteRenderHelper.fallbackRenderSprite} 逐行一致
 * （枢轴/旋转/四角顺序），再左乘收集时刻的 MVP 矩阵（projection×modelview 的 2D 仿射）把顶点烘焙到裁剪空间，
 * 使延迟 flush 时无需依赖当时的矩阵栈。顶点格式与引擎合批 VBO 路径一致：
 * x,y,u,v（float×4）+ r,g,b,a（ubyte×4），20 字节/顶点；每 quad 6 索引（0,1,2 / 0,2,3）。
 */
public final class SpriteQuadPacker {
    private static final float DEG_TO_RAD = 0.017453292519943295769f;

    /** 单顶点字节数：x,y,u,v（float×4）+ r,g,b,a（ubyte×4）。 */
    public static final int VERTEX_BYTES = 20;
    /** 单次 draw 的最大 quad 数（16 位索引上限 65535 / 4 顶点）。 */
    public static final int MAX_QUADS_PER_DRAW = 16383;

    private SpriteQuadPacker() {
    }

    /**
     * 向顶点/索引缓冲追加一个烘焙到裁剪空间的 quad。
     *
     * @param verts     顶点缓冲（position 处写入 80 字节）
     * @param indices   索引缓冲（position 处写入 6 个 short）
     * @param baseVertex 本 quad 的起始顶点下标（quad 序号 × 4）
     * @param mv        收集时刻的 MVP 2D 仿射矩阵（列主序 16 元素，只用槽位 0/1/4/5/12/13）
     * @param posX      sprite 左下角 X（已含 offsetX）
     * @param posY      sprite 左下角 Y（已含 offsetY）
     * @param width     sprite 宽
     * @param height    sprite 高
     * @param centerX   旋转枢轴 X（-1 表示取 width/2）
     * @param centerY   旋转枢轴 Y（-1 表示取 height/2）
     * @param angle     旋转角（度，绕枢轴）
     * @param r         顶点色 R（0..255，按原版 (byte) 截断语义）
     * @param g         顶点色 G
     * @param b         顶点色 B
     * @param a         顶点色 A（color.getAlpha() * alphaMult 的 int 截断值）
     * @param texX      UV 起点 U
     * @param texY      UV 起点 V
     * @param texWidth  UV 宽
     * @param texHeight UV 高
     */
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

        // 原版 quad-strip 顶点序：左下 → 左上 → 右上 → 右下
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

    /** 局部角点旋转 + 平移到原点 + 左乘 MVP 2D 仿射，结果写入 out（裁剪空间 x, y）。 */
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
