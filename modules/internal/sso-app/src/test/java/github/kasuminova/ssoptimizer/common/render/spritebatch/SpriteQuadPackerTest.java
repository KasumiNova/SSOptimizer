package github.kasuminova.ssoptimizer.common.render.spritebatch;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprite quad 顶点打包测试：几何公式与 {@code SpriteRenderHelper.fallbackRenderSprite}
 * 同式（枢轴/旋转/四角顺序），此处验证烘焙 modelview 后的观察空间坐标、UV 与颜色字节、索引模板。
 */
class SpriteQuadPackerTest {

    private static ByteBuffer verts() {
        return ByteBuffer.allocateDirect(4 * SpriteQuadPacker.VERTEX_BYTES).order(ByteOrder.nativeOrder());
    }

    private static ByteBuffer indices() {
        return ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder());
    }

    private static float[] identity() {
        float[] mv = new float[16];
        mv[0] = 1.0f;
        mv[5] = 1.0f;
        mv[10] = 1.0f;
        mv[15] = 1.0f;
        return mv;
    }

    @Test
    void identityMatrixNoRotationPacksCornersAndUv() {
        ByteBuffer v = verts();
        ByteBuffer idx = indices();
        // pos=(10,20) w=4 h=2 center=-1（取半宽半高）angle=0
        SpriteQuadPacker.packQuad(v, idx, 0, identity(),
                10, 20, 4, 2, -1, -1, 0,
                255, 128, 0, 200,
                0.25f, 0.5f, 0.5f, 0.25f);
        v.flip();
        idx.flip();

        // 四角（左下→左上→右上→右下），无旋转时退化为轴对齐矩形
        float[][] expected = {{10, 20}, {10, 22}, {14, 22}, {14, 20}};
        float[][] expectedUv = {{0.25f, 0.5f}, {0.25f, 0.75f}, {0.75f, 0.75f}, {0.75f, 0.5f}};
        for (int i = 0; i < 4; i++) {
            assertEquals(expected[i][0], v.getFloat(), 1e-6, "v" + i + ".x");
            assertEquals(expected[i][1], v.getFloat(), 1e-6, "v" + i + ".y");
            assertEquals(expectedUv[i][0], v.getFloat(), 1e-6, "v" + i + ".u");
            assertEquals(expectedUv[i][1], v.getFloat(), 1e-6, "v" + i + ".v");
            assertEquals((byte) 255, v.get(), "v" + i + ".r");
            assertEquals((byte) 128, v.get(), "v" + i + ".g");
            assertEquals((byte) 0, v.get(), "v" + i + ".b");
            assertEquals((byte) 200, v.get(), "v" + i + ".a");
        }

        int[] expectedIdx = {0, 1, 2, 0, 2, 3};
        for (int e : expectedIdx) {
            assertEquals(e, idx.getShort(), "索引序列");
        }
    }

    @Test
    void rotationMatchesHelperFormula() {
        ByteBuffer v = verts();
        // w=h=2，pos=(0,0)，center 默认 → 枢轴 (1,1)，angle=90°
        // 左下角局部 (-1,-1) 旋转 90° → (1,-1) → 世界 (2,0)
        SpriteQuadPacker.packQuad(v, indices(), 0, identity(),
                0, 0, 2, 2, -1, -1, 90,
                255, 255, 255, 255,
                0, 0, 1, 1);
        v.flip();
        assertEquals(2.0f, v.getFloat(), 1e-5, "旋转后左下角 x");
        assertEquals(0.0f, v.getFloat(), 1e-5, "旋转后左下角 y");
    }

    @Test
    void customPivotAndModelviewTranslation() {
        ByteBuffer v = verts();
        float[] mv = identity();
        mv[12] = 100.0f;
        mv[13] = 50.0f;
        // pos=(0,0) w=4 h=4 center=(2,2)（与默认相同位置但走显式枢轴分支）angle=0
        SpriteQuadPacker.packQuad(v, indices(), 0, mv,
                0, 0, 4, 4, 2, 2, 0,
                255, 255, 255, 255,
                0, 0, 1, 1);
        v.flip();
        // 左下角 = (0,0) + modelview 平移 (100,50)
        assertEquals(100.0f, v.getFloat(), 1e-6, "mv 平移后 x");
        assertEquals(50.0f, v.getFloat(), 1e-6, "mv 平移后 y");
    }

    @Test
    void baseVertexOffsetsIndices() {
        ByteBuffer idx = indices();
        SpriteQuadPacker.packQuad(verts(), idx, 8, identity(),
                0, 0, 1, 1, -1, -1, 0,
                0, 0, 0, 0,
                0, 0, 1, 1);
        idx.flip();
        int[] expectedIdx = {8, 9, 10, 8, 10, 11};
        for (int e : expectedIdx) {
            assertEquals(e, idx.getShort(), "索引基址偏移");
        }
    }
}
