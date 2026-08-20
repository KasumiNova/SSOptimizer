package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link VertexArrayBatch} 的合并构建逻辑验证（无 GL 环境，只断言段/操作/顶点
 * 数组内容；真实 {@code glDrawArrays} 提交侧保持薄逻辑，由冒烟与基准验收）：
 * 顶点属性快照、状态指令的段分隔顺序、属性段内激活切分、颜色精度收窄、
 * 空段剔除与回放回调重置。
 */
class VertexArrayBatchTest {

    /** 经 VertexStream 编码-回放闭环驱动合并器（同时验证 startReplay/finishReplay 接线）。 */
    private static VertexArrayBatch replay(VertexStream stream) {
        VertexArrayBatch batch = new VertexArrayBatch();
        byte[] data = new byte[stream.length()];
        stream.copyTo(data);
        VertexStream.replay(data, data.length, batch);
        return batch;
    }

    @Test
    void singleQuadSnapshot() {
        VertexStream stream = new VertexStream();
        stream.begin(GL11.GL_QUADS);
        stream.texCoord2f(0.25f, 0.75f);
        stream.color4ub((byte) 10, (byte) 20, (byte) 30, (byte) 40);
        stream.vertex2f(1.0f, 2.0f);
        stream.vertex2f(3.0f, 2.0f);
        stream.texCoord2f(0.5f, 0.75f);
        stream.vertex2f(3.0f, 4.0f);
        stream.vertex2f(1.0f, 4.0f);
        stream.end();

        VertexArrayBatch batch = replay(stream);

        assertEquals(1, batch.opCount());
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(0));
        assertEquals(GL11.GL_QUADS, batch.opArg(0, 0));
        assertEquals(0, batch.opArg(0, 1));
        assertEquals(4, batch.opArg(0, 2));
        assertEquals(VertexArrayBatch.FLAG_TEX | VertexArrayBatch.FLAG_COLOR, batch.opDrawFlags(0));

        assertEquals(4, batch.vertexCount());
        // 顶点 0 快照的是录制时的 tex/color 状态
        assertEquals(0.25f, batch.texAt(0, 0));
        assertEquals(0.75f, batch.texAt(0, 1));
        assertEquals(10, batch.colorAt(0, 0));
        assertEquals(40, batch.colorAt(0, 3));
        // 顶点 2 的 tex 已更新，颜色保持
        assertEquals(0.5f, batch.texAt(2, 0));
        assertEquals(10, batch.colorAt(2, 0));
        // z 分量补 0
        assertEquals(0.0f, batch.posAt(0, 2));
    }

    @Test
    void bindTextureSeparatesDrawOpsInOrder() {
        VertexStream stream = new VertexStream();
        stream.begin(GL11.GL_QUADS);
        stream.vertex2f(0.0f, 0.0f);
        stream.vertex2f(1.0f, 0.0f);
        stream.end();
        stream.bindTexture(1234);
        stream.begin(GL11.GL_QUADS);
        stream.vertex2f(2.0f, 0.0f);
        stream.vertex2f(3.0f, 0.0f);
        stream.end();

        VertexArrayBatch batch = replay(stream);

        assertEquals(3, batch.opCount());
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(0));
        assertEquals(VertexArrayBatch.OP_BIND_TEXTURE, batch.opKind(1));
        assertEquals(1234, batch.opArg(1, 0));
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(2));
        // 第二段 DRAW 的 first 指向顶点 2
        assertEquals(2, batch.opArg(2, 1));
        assertEquals(2, batch.opArg(2, 2));
    }

    @Test
    void attributeActivationSplitsOpenSegment() {
        VertexStream stream = new VertexStream();
        stream.begin(GL11.GL_TRIANGLE_STRIP);
        stream.vertex2f(0.0f, 0.0f);
        stream.vertex2f(1.0f, 0.0f);
        stream.texCoord2f(0.5f, 0.5f);
        stream.vertex2f(2.0f, 0.0f);
        stream.end();

        VertexArrayBatch batch = replay(stream);

        assertEquals(2, batch.opCount());
        // 前半段顶点无 tex 数组（用回放现场外部当前值），后半段启用
        assertEquals(0, batch.opDrawFlags(0));
        assertEquals(0, batch.opArg(0, 1));
        assertEquals(2, batch.opArg(0, 2));
        assertEquals(VertexArrayBatch.FLAG_TEX, batch.opDrawFlags(1));
        assertEquals(2, batch.opArg(1, 1));
        assertEquals(1, batch.opArg(1, 2));
        // 段内激活后的顶点带着激活时的 tex 快照
        assertEquals(0.5f, batch.texAt(2, 0));
    }

    @Test
    void color3fConvertsToUnsignedBytes() {
        VertexStream stream = new VertexStream();
        stream.begin(GL11.GL_QUADS);
        stream.color3f(1.0f, 0.5f, 0.0f);
        stream.vertex2f(0.0f, 0.0f);
        stream.end();

        VertexArrayBatch batch = replay(stream);

        assertEquals(VertexArrayBatch.FLAG_COLOR, batch.opDrawFlags(0));
        assertEquals(255, batch.colorAt(0, 0));
        assertEquals(128, batch.colorAt(0, 1));
        assertEquals(0, batch.colorAt(0, 2));
        assertEquals(255, batch.colorAt(0, 3));
    }

    @Test
    void emptySegmentsProduceNoDraw() {
        VertexStream stream = new VertexStream();
        stream.begin(GL11.GL_QUADS);
        stream.end();
        stream.enable(GL11.GL_BLEND);
        stream.begin(GL11.GL_QUADS);
        stream.end();

        VertexArrayBatch batch = replay(stream);

        assertEquals(1, batch.opCount());
        assertEquals(VertexArrayBatch.OP_ENABLE, batch.opKind(0));
        assertEquals(GL11.GL_BLEND, batch.opArg(0, 0));
        assertEquals(0, batch.vertexCount());
    }

    @Test
    void stateOpsKeepRecordingOrder() {
        VertexStream stream = new VertexStream();
        stream.enable(GL11.GL_BLEND);
        stream.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        stream.begin(GL11.GL_QUADS);
        stream.vertex2f(0.0f, 0.0f);
        stream.end();
        stream.disable(GL11.GL_BLEND);

        VertexArrayBatch batch = replay(stream);

        assertEquals(4, batch.opCount());
        assertEquals(VertexArrayBatch.OP_ENABLE, batch.opKind(0));
        assertEquals(VertexArrayBatch.OP_BLEND_FUNC, batch.opKind(1));
        assertEquals(GL11.GL_SRC_ALPHA, batch.opArg(1, 0));
        assertEquals(GL11.GL_ONE_MINUS_SRC_ALPHA, batch.opArg(1, 1));
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(2));
        assertEquals(VertexArrayBatch.OP_DISABLE, batch.opKind(3));
    }

    @Test
    void startReplayResetsAccumulatedState() {
        VertexArrayBatch batch = new VertexArrayBatch();
        VertexStream first = new VertexStream();
        first.begin(GL11.GL_QUADS);
        first.color4ub((byte) 1, (byte) 2, (byte) 3, (byte) 4);
        first.vertex2f(0.0f, 0.0f);
        first.end();
        byte[] firstData = new byte[first.length()];
        first.copyTo(firstData);
        VertexStream.replay(firstData, firstData.length, batch);
        assertEquals(1, batch.vertexCount());

        VertexStream second = new VertexStream();
        second.begin(GL11.GL_QUADS);
        second.vertex2f(9.0f, 9.0f);
        second.end();
        byte[] secondData = new byte[second.length()];
        second.copyTo(secondData);
        VertexStream.replay(secondData, secondData.length, batch);

        // 第二次回放从零开始：上一批次的顶点计数/颜色定义不残留
        assertEquals(1, batch.vertexCount());
        assertEquals(1, batch.opCount());
        assertEquals(0, batch.opDrawFlags(0));
        assertEquals(9.0f, batch.posAt(0, 0));
    }

    @Test
    void spriteSequenceCollapsesToOneDrawPerStateRun() {
        // 模拟 SpriteRenderHelper.fallbackRenderSprite 的每 sprite 流内状态对：
        // 三个同混合状态的连续 sprite 应收敛为「一组状态调用 + 单次合并 DRAW + 收尾 disable」。
        VertexStream stream = new VertexStream();
        for (int i = 0; i < 3; i++) {
            stream.color4ub((byte) 255, (byte) 255, (byte) 255, (byte) 255);
            stream.enable(GL11.GL_TEXTURE_2D);
            stream.enable(GL11.GL_BLEND);
            stream.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            stream.begin(GL11.GL_QUADS);
            stream.texCoord2f(0.0f, 0.0f);
            stream.vertex2f(i * 10.0f, 0.0f);
            stream.vertex2f(i * 10.0f + 5.0f, 0.0f);
            stream.vertex2f(i * 10.0f + 5.0f, 5.0f);
            stream.vertex2f(i * 10.0f, 5.0f);
            stream.end();
            stream.disable(GL11.GL_BLEND);
        }

        VertexArrayBatch batch = replay(stream);

        assertEquals(7, batch.opCount());
        assertEquals(VertexArrayBatch.OP_ENABLE, batch.opKind(0));
        assertEquals(GL11.GL_TEXTURE_2D, batch.opArg(0, 0));
        assertEquals(VertexArrayBatch.OP_ENABLE, batch.opKind(1));
        assertEquals(GL11.GL_BLEND, batch.opArg(1, 0));
        assertEquals(VertexArrayBatch.OP_BLEND_FUNC, batch.opKind(2));
        // 三个 quad 合并为一次 draw（12 顶点）
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(3));
        assertEquals(0, batch.opArg(3, 1));
        assertEquals(12, batch.opArg(3, 2));
        // 中间两对 disable/enable 被抵消为 NOOP，末位 disable 保留以复原状态
        assertEquals(VertexArrayBatch.OP_NOOP, batch.opKind(4));
        assertEquals(VertexArrayBatch.OP_NOOP, batch.opKind(5));
        assertEquals(VertexArrayBatch.OP_DISABLE, batch.opKind(6));
        assertEquals(GL11.GL_BLEND, batch.opArg(6, 0));
    }

    @Test
    void disableObservedByDrawIsKept() {
        VertexStream stream = new VertexStream();
        stream.enable(GL11.GL_BLEND);
        stream.begin(GL11.GL_QUADS);
        stream.vertex2f(0.0f, 0.0f);
        stream.end();
        stream.disable(GL11.GL_BLEND);
        stream.begin(GL11.GL_QUADS);
        stream.vertex2f(1.0f, 1.0f);
        stream.end();
        stream.enable(GL11.GL_BLEND);

        VertexArrayBatch batch = replay(stream);

        // disable 被中间的 draw 观测，状态对不可抵消，两段 DRAW 也不得跨其合并
        assertEquals(5, batch.opCount());
        assertEquals(VertexArrayBatch.OP_ENABLE, batch.opKind(0));
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(1));
        assertEquals(1, batch.opArg(1, 2));
        assertEquals(VertexArrayBatch.OP_DISABLE, batch.opKind(2));
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(3));
        assertEquals(1, batch.opArg(3, 1));
        assertEquals(1, batch.opArg(3, 2));
        assertEquals(VertexArrayBatch.OP_ENABLE, batch.opKind(4));
    }

    @Test
    void unobservedBlendFuncIsOverwrittenInPlace() {
        VertexStream stream = new VertexStream();
        stream.blendFunc(GL11.GL_ONE, GL11.GL_ZERO);
        stream.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        stream.begin(GL11.GL_QUADS);
        stream.vertex2f(0.0f, 0.0f);
        stream.end();

        VertexArrayBatch batch = replay(stream);

        // 第一条 blendFunc 未被任何 draw 观测：原地改写为后值，不产生第二条
        assertEquals(2, batch.opCount());
        assertEquals(VertexArrayBatch.OP_BLEND_FUNC, batch.opKind(0));
        assertEquals(GL11.GL_SRC_ALPHA, batch.opArg(0, 0));
        assertEquals(GL11.GL_ONE_MINUS_SRC_ALPHA, batch.opArg(0, 1));
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(1));
    }

    @Test
    void sameTextureRebindIsSkippedAndDrawsMerge() {
        VertexStream stream = new VertexStream();
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                stream.bindTexture(5);
            }
            stream.begin(GL11.GL_QUADS);
            stream.texCoord2f(0.0f, 0.0f);
            stream.vertex2f(i, 0.0f);
            stream.vertex2f(i + 1.0f, 0.0f);
            stream.end();
        }

        VertexArrayBatch batch = replay(stream);

        // 首次绑定保留，第二次同值绑定跳过，其后两段 DRAW 合并
        assertEquals(3, batch.opCount());
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(0));
        assertEquals(2, batch.opArg(0, 2));
        assertEquals(VertexArrayBatch.OP_BIND_TEXTURE, batch.opKind(1));
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(2));
        assertEquals(2, batch.opArg(2, 1));
        assertEquals(4, batch.opArg(2, 2));
    }

    @Test
    void spriteQuadRoundTrip() {
        VertexStream stream = new VertexStream();
        stream.color4ub((byte) 255, (byte) 0, (byte) 0, (byte) 255);
        stream.spriteQuad(
                10.0f, 20.0f, 10.0f, 30.0f, 20.0f, 30.0f, 20.0f, 20.0f,
                0.25f, 0.5f, 0.125f, 0.25f);

        VertexArrayBatch batch = replay(stream);

        assertEquals(1, batch.opCount());
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(0));
        assertEquals(GL11.GL_QUADS, batch.opArg(0, 0));
        assertEquals(4, batch.opArg(0, 2));
        assertEquals(VertexArrayBatch.FLAG_TEX | VertexArrayBatch.FLAG_COLOR, batch.opDrawFlags(0));
        // 角点 tex 与逐顶点展开语义一致
        assertEquals(0.25f, batch.texAt(0, 0));
        assertEquals(0.5f, batch.texAt(0, 1));
        assertEquals(0.75f, batch.texAt(1, 1));
        assertEquals(0.375f, batch.texAt(2, 0));
        assertEquals(0.5f, batch.texAt(3, 1));
        assertEquals(20.0f, batch.posAt(3, 0));
        assertEquals(255, batch.colorAt(0, 0));
    }

    @Test
    void mergedRunDeduplicatesAcrossBatchBoundary() {
        // 模拟渲染线程串协议（VertexBatchCommand.executeMerged）：两条相邻批次
        // 共享合并器——跨批次的 disable→enable 状态对被抵消，相邻 DRAW 跨批次合并。
        VertexArrayBatch batch = new VertexArrayBatch();
        batch.startReplay();
        for (int i = 0; i < 2; i++) {
            VertexStream stream = new VertexStream();
            stream.enable(GL11.GL_BLEND);
            stream.begin(GL11.GL_QUADS);
            stream.vertex2f(i * 10.0f, 0.0f);
            stream.vertex2f(i * 10.0f + 5.0f, 0.0f);
            stream.vertex2f(i * 10.0f + 5.0f, 5.0f);
            stream.vertex2f(i * 10.0f, 5.0f);
            stream.end();
            stream.disable(GL11.GL_BLEND);
            byte[] data = new byte[stream.length()];
            stream.copyTo(data);
            VertexStream.replayBody(data, data.length, batch);
        }
        batch.finishReplay();

        assertEquals(4, batch.opCount());
        assertEquals(VertexArrayBatch.OP_ENABLE, batch.opKind(0));
        // 两个批次的 quad 合并为一次 draw（8 顶点）
        assertEquals(VertexArrayBatch.OP_DRAW, batch.opKind(1));
        assertEquals(0, batch.opArg(1, 1));
        assertEquals(8, batch.opArg(1, 2));
        // 跨批次的 disable→enable 对抵消为 NOOP，末位 disable 保留
        assertEquals(VertexArrayBatch.OP_NOOP, batch.opKind(2));
        assertEquals(VertexArrayBatch.OP_DISABLE, batch.opKind(3));
        assertEquals(8, batch.vertexCount());
    }

    @Test
    void startReplayResetsSealedBufferLimits() {
        // 模拟生产路径：小批次回放后 executeGl 收窄 limit，随后更大批次
        // 必须能完整写入（游戏加载画面第二帧即触发此序列的越界崩溃）。
        VertexArrayBatch batch = new VertexArrayBatch();
        VertexStream small = new VertexStream();
        small.begin(GL11.GL_QUADS);
        small.vertex2f(0.0f, 0.0f);
        small.end();
        byte[] smallData = new byte[small.length()];
        small.copyTo(smallData);
        VertexStream.replay(smallData, smallData.length, batch);
        batch.sealBuffers();

        VertexStream large = new VertexStream();
        large.begin(GL11.GL_TRIANGLES);
        for (int i = 0; i < 3000; i++) {
            large.vertex3f(i, i + 1, i + 2);
        }
        large.end();
        byte[] largeData = new byte[large.length()];
        large.copyTo(largeData);
        VertexStream.replay(largeData, largeData.length, batch);

        assertEquals(3000, batch.vertexCount());
        assertEquals(2999.0f, batch.posAt(2999, 0));
    }

    @Test
    void vertexCapacityGrowsForLargeBatch() {
        VertexStream stream = new VertexStream();
        stream.begin(GL11.GL_TRIANGLES);
        for (int i = 0; i < 5000; i++) {
            stream.vertex3f(i, i + 1, i + 2);
        }
        stream.end();

        VertexArrayBatch batch = replay(stream);

        assertEquals(5000, batch.vertexCount());
        assertEquals(1, batch.opCount());
        assertEquals(5000, batch.opArg(0, 2));
        assertEquals(4999.0f, batch.posAt(4999, 0));
        assertEquals(5001.0f, batch.posAt(4999, 2));
    }
}
