package github.kasuminova.ssoptimizer.common.loading;

import org.json.JSONException;
import org.json.JSONObject;

public final class LoadingJsonCommentStripper {

    private LoadingJsonCommentStripper() {
    }

    public static JSONObject parse(final String sourceName, final String text) throws JSONException {
        try {
            return new JSONObject(strip(text));
        } catch (final JSONException e) {
            throw new JSONException(sourceName + "\n" + e.getMessage());
        }
    }

    // 为兼容原版解析，换行会同时重置注释与字符串状态，且仅保留 \n。
    static String strip(final String text) {
        final StringBuilder result = new StringBuilder(text.length());
        boolean inComment = false;
        boolean inString = false;

        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '"') {
                inString = !inString;
            }

            if (c == '\n' || c == '\r') {
                inComment = false;
                inString = false;
                if (c == '\n') {
                    result.append('\n');
                }
            } else if (c == '#' && !inString) {
                inComment = true;
            } else if (!inComment) {
                result.append(c);
            }
        }

        return result.toString();
    }
}
