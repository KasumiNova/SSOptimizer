package github.kasuminova.ssoptimizer.common.loading;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LoadingJsonCommentStripper 的完整逻辑验证：与原版逐字符剥离语义逐项对齐。
 */
class LoadingJsonCommentStripperTest {

    @Test
    void stripsHashCommentsOutsideStrings() throws org.json.JSONException {
        final String text = "{\n"
                + "# 整行注释\n"
                + "  \"a\": 1, # 行尾注释\n"
                + "  \"b\": \"# 不是注释\"\n"
                + "}";
        final JSONObject json = LoadingJsonCommentStripper.parse("test.json", text);
        assertEquals(1, json.getInt("a"));
        assertEquals("# 不是注释", json.getString("b"));
    }

    @Test
    void carriageReturnsAreDroppedAndNewlinesKept() {
        // 原版语义：\r 直接丢弃，\n 保留且重置注释/字符串状态（注释行尾的 \n 同样保留）
        final String stripped = LoadingJsonCommentStripper.strip("a\r\nb\rc\n#x\r\nd");
        assertEquals("a\nbc\n\nd", stripped);
    }

    @Test
    void newlineResetsStringStateLikeVanilla() {
        // 原版在换行处无条件重置 inString：跨行字符串的后续 # 会被当作注释起点
        final String stripped = LoadingJsonCommentStripper.strip("\"ab\ncd#ef\"\n1");
        assertEquals("\"ab\ncd\n1", stripped);
    }

    @Test
    void quoteTogglesStringStateIncludingEscaped() {
        // 原版不识别转义：每个 " 都翻转字符串状态
        final String stripped = LoadingJsonCommentStripper.strip("\"a\\\" # c\nb");
        assertEquals("\"a\\\" \nb", stripped);
    }

    @Test
    void parseFailurePrefixesSourceName() {
        final Exception e = assertThrows(org.json.JSONException.class,
                () -> LoadingJsonCommentStripper.parse("data/x.json", "{ 非法 "));
        assertTrue(e.getMessage().startsWith("data/x.json\n"), e.getMessage());
    }
}
