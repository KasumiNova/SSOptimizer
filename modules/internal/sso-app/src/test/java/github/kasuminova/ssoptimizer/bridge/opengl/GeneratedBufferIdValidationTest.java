package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
