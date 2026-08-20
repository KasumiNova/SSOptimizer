package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VBO id 批发结果的 fail-fast 校验（{@link BridgeSupport#validateGeneratedBufferIds}）：
 * 全有效放行；含无效 id（<1）时在生成点抛出带 GL 错误码与定位信息的
 * IllegalStateException，拒绝把死 id 分发进 stash 或调用方。
 */
class GeneratedBufferIdValidationTest {

    @Test
    void validBatchPasses() {
        int[] batch = new int[BridgeSupport.BUFFER_ID_STASH_BATCH];
        for (int i = 0; i < batch.length; i++) {
            batch[i] = i + 1;
        }
        assertDoesNotThrow(() -> BridgeSupport.validateGeneratedBufferIds(batch, () -> 0));
    }

    @Test
    void invalidIdFailsFastWithDiagnostics() {
        int[] batch = new int[BridgeSupport.BUFFER_ID_STASH_BATCH];
        for (int i = 0; i < batch.length; i++) {
            batch[i] = i + 1;
        }
        batch[7] = 0;
        batch[9] = 0;
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BridgeSupport.validateGeneratedBufferIds(batch, () -> 0x502));
        assertTrue(e.getMessage().contains("2/" + BridgeSupport.BUFFER_ID_STASH_BATCH));
        assertTrue(e.getMessage().contains("下标 7"));
        assertTrue(e.getMessage().contains("0x00000502"));
    }
}
