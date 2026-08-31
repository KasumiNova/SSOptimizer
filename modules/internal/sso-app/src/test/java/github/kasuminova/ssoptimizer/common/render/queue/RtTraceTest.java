package github.kasuminova.ssoptimizer.common.render.queue;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RtTrace#parseWatchTex(String)} 的解析逻辑验证。
 */
class RtTraceTest {

    @Test
    void parseWatchTex_emptyOrNull_returnsEmpty() {
        assertTrue(RtTrace.parseWatchTex(null).isEmpty());
        assertTrue(RtTrace.parseWatchTex("").isEmpty());
        assertTrue(RtTrace.parseWatchTex("   ").isEmpty());
    }

    @Test
    void parseWatchTex_validIds_parsedWithTrim() {
        assertEquals(Set.of(3588, 3590), RtTrace.parseWatchTex("3588, 3590"));
        assertEquals(Set.of(1), RtTrace.parseWatchTex("1"));
    }

    @Test
    void parseWatchTex_invalidToken_skipped() {
        assertEquals(Set.of(42), RtTrace.parseWatchTex("abc,42,,xyz"));
    }
}
