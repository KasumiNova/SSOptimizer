package github.kasuminova.ssoptimizer.common.render.warroom;

import com.fs.graphics.TextureObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WarroomTaskLineBatch} 的行为单测。
 * <p>
 * 通过记录型 {@link StripBatchRenderer} 伪执行器真实驱动 begin/add/end 全流程，
 * 验证顶点收集顺序、颜色打包、全透明剔除、纹理/混合模式切换分段与容量增长逻辑。
 */
class WarroomTaskLineBatchTest {

    /** 记录型伪执行器：保存每次 flush 的快照供断言。 */
    private static final class RecordingRenderer implements StripBatchRenderer {
        final List<TextureObject> textures = new ArrayList<>();
        final List<Boolean> additiveFlags = new ArrayList<>();
        final List<Integer> stripCounts = new ArrayList<>();
        final List<float[]> geometries = new ArrayList<>();
        final List<int[]> colorArrays = new ArrayList<>();

        @Override
        public void renderStripBatch(TextureObject texture, boolean additive,
                                     float[] geometry, int[] colors, int stripCount) {
            textures.add(texture);
            additiveFlags.add(additive);
            stripCounts.add(stripCount);
            float[] geometryCopy = new float[stripCount * WarroomTaskLineBatch.FLOATS_PER_STRIP];
            System.arraycopy(geometry, 0, geometryCopy, 0, geometryCopy.length);
            geometries.add(geometryCopy);
            int[] colorsCopy = new int[stripCount];
            System.arraycopy(colors, 0, colorsCopy, 0, stripCount);
            colorArrays.add(colorsCopy);
        }
    }

    @AfterEach
    void resetBatch() {
        // 用空收集周期复位静态状态，避免用例间串扰。
        WarroomTaskLineBatch.beginCollect();
        WarroomTaskLineBatch.endCollect(new RecordingRenderer());
    }

    @Test
    void collectsStripsInCallOrderAndFlushesOnceOnEnd() {
        RecordingRenderer renderer = new RecordingRenderer();
        TextureObject texture = new TextureObject(3553, 1);
        Color color = new Color(10, 20, 30, 40);

        WarroomTaskLineBatch.beginCollect();
        assertTrue(WarroomTaskLineBatch.isCollecting());
        WarroomTaskLineBatch.addStrip(texture, 1f, 2f, 3f, 4f, 5f, 6f, color, 0.1f, 0.2f, 0.3f, false, renderer);
        WarroomTaskLineBatch.addStrip(texture, 11f, 12f, 13f, 14f, 15f, 16f, color, 0.4f, 0.5f, 0.6f, false, renderer);
        assertEquals(2, WarroomTaskLineBatch.getStripCount());
        WarroomTaskLineBatch.endCollect(renderer);

        assertFalse(WarroomTaskLineBatch.isCollecting());
        assertEquals(0, WarroomTaskLineBatch.getStripCount());
        assertEquals(1, renderer.stripCounts.size());
        assertEquals(2, renderer.stripCounts.get(0));
        assertSame(texture, renderer.textures.get(0));
        assertFalse(renderer.additiveFlags.get(0));

        float[] geometry = renderer.geometries.get(0);
        // 第一条带的 9 个 float 必须按调用参数原样排布。
        assertArrayEquals(new float[]{1f, 2f, 3f, 4f, 5f, 6f, 0.1f, 0.2f, 0.3f},
                Arrays.copyOfRange(geometry, 0, 9));
        assertArrayEquals(new float[]{11f, 12f, 13f, 14f, 15f, 16f, 0.4f, 0.5f, 0.6f},
                Arrays.copyOfRange(geometry, 9, 18));

        int packed = renderer.colorArrays.get(0)[0];
        assertEquals(10, (packed >>> 24) & 0xFF);
        assertEquals(20, (packed >>> 16) & 0xFF);
        assertEquals(30, (packed >>> 8) & 0xFF);
        assertEquals(40, packed & 0xFF);
    }

    @Test
    void fullyTransparentStripsAreCulledAtCollectTime() {
        RecordingRenderer renderer = new RecordingRenderer();
        TextureObject texture = new TextureObject(3553, 1);

        WarroomTaskLineBatch.beginCollect();
        // 已摧毁舰船的连线：三段透明度缩放全为 0，不可见，应被剔除。
        WarroomTaskLineBatch.addStrip(texture, 0f, 0f, 1f, 1f, 2f, 2f,
                new Color(255, 255, 255, 255), 0f, 0f, 0f, false, renderer);
        // 负缩放会被钳制到 0，同样不可见。
        WarroomTaskLineBatch.addStrip(texture, 0f, 0f, 1f, 1f, 2f, 2f,
                new Color(255, 255, 255, 255), -1f, -0.5f, 0f, false, renderer);
        // 颜色 alpha 为 0：任何缩放下都不透明写入。
        WarroomTaskLineBatch.addStrip(texture, 0f, 0f, 1f, 1f, 2f, 2f,
                new Color(255, 255, 255, 0), 1f, 1f, 1f, false, renderer);
        // 可见条带：保留。
        WarroomTaskLineBatch.addStrip(texture, 0f, 0f, 1f, 1f, 2f, 2f,
                new Color(255, 255, 255, 255), 0f, 0.5f, 1f, false, renderer);
        assertEquals(1, WarroomTaskLineBatch.getStripCount());
        WarroomTaskLineBatch.endCollect(renderer);

        assertEquals(1, renderer.stripCounts.size());
        assertEquals(1, renderer.stripCounts.get(0));
    }

    @Test
    void emptyBatchEndsWithoutRenderCall() {
        RecordingRenderer renderer = new RecordingRenderer();

        WarroomTaskLineBatch.beginCollect();
        WarroomTaskLineBatch.endCollect(renderer);

        assertTrue(renderer.stripCounts.isEmpty());
    }

    @Test
    void textureChangeFlushesPreviousSegmentPreservingOrder() {
        RecordingRenderer renderer = new RecordingRenderer();
        TextureObject textureA = new TextureObject(3553, 1);
        TextureObject textureB = new TextureObject(3553, 2);
        Color color = new Color(255, 255, 255, 255);

        WarroomTaskLineBatch.beginCollect();
        WarroomTaskLineBatch.addStrip(textureA, 1f, 1f, 2f, 2f, 3f, 3f, color, 1f, 1f, 1f, false, renderer);
        WarroomTaskLineBatch.addStrip(textureA, 4f, 4f, 5f, 5f, 6f, 6f, color, 1f, 1f, 1f, false, renderer);
        // 换纹理：应先 flush A 段两条，再开启 B 段。
        WarroomTaskLineBatch.addStrip(textureB, 7f, 7f, 8f, 8f, 9f, 9f, color, 1f, 1f, 1f, false, renderer);
        WarroomTaskLineBatch.endCollect(renderer);

        assertEquals(2, renderer.stripCounts.size());
        assertEquals(2, renderer.stripCounts.get(0));
        assertSame(textureA, renderer.textures.get(0));
        assertEquals(1, renderer.stripCounts.get(1));
        assertSame(textureB, renderer.textures.get(1));
        assertEquals(1f, renderer.geometries.get(0)[0]);
        assertEquals(7f, renderer.geometries.get(1)[0]);
    }

    @Test
    void additiveChangeFlushesPreviousSegment() {
        RecordingRenderer renderer = new RecordingRenderer();
        TextureObject texture = new TextureObject(3553, 1);
        Color color = new Color(255, 255, 255, 255);

        WarroomTaskLineBatch.beginCollect();
        WarroomTaskLineBatch.addStrip(texture, 1f, 1f, 2f, 2f, 3f, 3f, color, 1f, 1f, 1f, false, renderer);
        WarroomTaskLineBatch.addStrip(texture, 4f, 4f, 5f, 5f, 6f, 6f, color, 1f, 1f, 1f, true, renderer);
        WarroomTaskLineBatch.endCollect(renderer);

        assertEquals(2, renderer.stripCounts.size());
        assertFalse(renderer.additiveFlags.get(0));
        assertTrue(renderer.additiveFlags.get(1));
    }

    @Test
    void addStripWithoutCollectingIsIgnored() {
        RecordingRenderer renderer = new RecordingRenderer();

        WarroomTaskLineBatch.addStrip(new TextureObject(3553, 1), 0f, 0f, 1f, 1f, 2f, 2f,
                new Color(255, 255, 255, 255), 1f, 1f, 1f, false, renderer);

        assertEquals(0, WarroomTaskLineBatch.getStripCount());
        assertTrue(renderer.stripCounts.isEmpty());
    }

    @Test
    void repeatedBeginResetsPendingStrips() {
        RecordingRenderer renderer = new RecordingRenderer();
        TextureObject texture = new TextureObject(3553, 1);
        Color color = new Color(255, 255, 255, 255);

        WarroomTaskLineBatch.beginCollect();
        WarroomTaskLineBatch.addStrip(texture, 1f, 1f, 2f, 2f, 3f, 3f, color, 1f, 1f, 1f, false, renderer);
        // 异常保护：上一次收集未 end 时再次 begin，丢弃残留重新计数。
        WarroomTaskLineBatch.beginCollect();
        assertEquals(0, WarroomTaskLineBatch.getStripCount());
        WarroomTaskLineBatch.endCollect(renderer);

        assertTrue(renderer.stripCounts.isEmpty());
    }

    @Test
    void capacityGrowsBeyondInitialSizeKeepingAllStrips() {
        RecordingRenderer renderer = new RecordingRenderer();
        TextureObject texture = new TextureObject(3553, 1);
        Color color = new Color(255, 255, 255, 255);

        WarroomTaskLineBatch.beginCollect();
        int total = 600;
        for (int i = 0; i < total; i++) {
            WarroomTaskLineBatch.addStrip(texture, i, i, i + 1, i + 1, 2f, 2f,
                    color, 1f, 1f, 1f, false, renderer);
        }
        assertEquals(total, WarroomTaskLineBatch.getStripCount());
        WarroomTaskLineBatch.endCollect(renderer);

        assertEquals(1, renderer.stripCounts.size());
        assertEquals(total, renderer.stripCounts.get(0));
        float[] geometry = renderer.geometries.get(0);
        // 容量翻倍扩容后，首尾条带数据均完整保序。
        assertEquals(0f, geometry[0]);
        int lastBase = (total - 1) * WarroomTaskLineBatch.FLOATS_PER_STRIP;
        assertEquals((float) (total - 1), geometry[lastBase]);
        assertEquals((float) total, geometry[lastBase + 2]);
    }
}
