package github.kasuminova.ssoptimizer.common.loading;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        final String stripped = LoadingJsonCommentStripper.strip("a\r\nb\rc\n#x\r\nd");
        assertEquals("a\nbc\n\nd", stripped);
    }

    @Test
    void newlineResetsStringStateLikeVanilla() {
        final String stripped = LoadingJsonCommentStripper.strip("\"ab\ncd#ef\"\n1");
        assertEquals("\"ab\ncd\n1", stripped);
    }

    @Test
    void quoteTogglesStringStateIncludingEscaped() {
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
