package ssoptimizer.standalone.mods;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

final class GameJson {
    private GameJson() {}

    static JsonObject read(Path path) throws IOException {
        String text = Files.readString(path);
        try (JsonReader in = new JsonReader(new StringReader(clean(text)))) {
            in.setStrictness(Strictness.LENIENT);
            JsonElement root = value(in);
            if (!root.isJsonObject() || in.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("文件必须包含单个 JSON 对象");
            }
            return root.getAsJsonObject();
        } catch (IOException | JsonSyntaxException | IllegalStateException e) {
            throw new IOException(path + ": " + e.getMessage(), e);
        }
    }

    private static JsonElement value(JsonReader in) throws IOException {
        return switch (in.peek()) {
            case BEGIN_OBJECT -> {
                in.beginObject();
                JsonObject result = new JsonObject();
                while (in.hasNext()) {
                    String key = in.nextName();
                    if (result.has(key)) throw new IOException("重复 JSON 字段：" + in.getPath());
                    result.add(key, value(in));
                }
                in.endObject();
                yield result;
            }
            case BEGIN_ARRAY -> {
                in.beginArray();
                JsonArray result = new JsonArray();
                while (in.hasNext()) result.add(value(in));
                in.endArray();
                yield result;
            }
            case STRING, NUMBER, BOOLEAN, NULL -> JsonParser.parseReader(in);
            default -> throw new IOException("缺少 JSON 值：" + in.getPath());
        };
    }

    // Gson 的宽松模式会把数组尾逗号读作 null；只清除结束括号前的分隔符，保留字符位置。
    private static String clean(String text) throws IOException {
        char[] chars = text.toCharArray();
        char quote = 0;
        int last = -1;
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) { quote = 0; last = i; }
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (Character.isWhitespace(c)) continue;
            char next = i + 1 < chars.length ? chars[i + 1] : 0;
            if (c == '#' || c == '/' && next == '/') {
                while (i < chars.length && chars[i] != '\n' && chars[i] != '\r') chars[i++] = ' ';
                i--;
                continue;
            }
            if (c == '/' && next == '*') {
                int end = text.indexOf("*/", i + 2);
                if (end < 0) throw new IOException("块注释未结束，字符位置 " + i);
                for (; i <= end + 1; i++) {
                    if (chars[i] != '\r' && chars[i] != '\n') chars[i] = ' ';
                }
                i--;
                continue;
            }
            if (c == ',' && (last < 0 || "{[,:".indexOf(chars[last]) >= 0)) {
                throw new IOException("分隔符前缺少值，字符位置 " + i);
            }
            if ((c == ']' || c == '}') && last >= 0 && chars[last] == ',') chars[last] = ' ';
            last = i;
        }
        return new String(chars);
    }

    static JsonArray array(JsonElement value, String where) throws IOException {
        if (value != null && value.isJsonArray()) return value.getAsJsonArray();
        throw new IOException(where + " 必须是数组");
    }

    static String string(JsonElement value, String where) throws IOException {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return value.getAsString();
        }
        throw new IOException(where + " 必须是字符串");
    }

    static String id(JsonElement value, String where) throws IOException {
        String id = string(value, where);
        if (id.isBlank() || id.contains("|")) throw new IOException(where + " 包含无效的模组 id：" + id);
        return id;
    }
}
