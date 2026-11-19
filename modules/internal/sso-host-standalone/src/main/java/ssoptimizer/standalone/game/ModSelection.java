package ssoptimizer.standalone.game;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import ssoptimizer.standalone.host.PreparedSources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ModSelection {
    private ModSelection() { }

    public static boolean canLaunchDirectly() {
        return PreparedSources.selectionProblem() == null;
    }

    public static boolean beforeClosingLauncher() {
        String problem = PreparedSources.selectionProblem();
        if (problem == null) return true;
        Path file = selectionFile();
        Set<String> prepared = PreparedSources.enabledIds();
        try {
            if (Files.exists(file)) {
                byte[] saved = Files.readAllBytes(file);
                Set<String> ids = parse(saved);
                if (!ids.equals(prepared)) requestRestart(prepared, ids, saved);
            }
            ModSelectionNotice.show(78, "当前模组组合无法启动游戏。请在模组管理器中调整选择。\n" + problem, null);
        } catch (IOException | JSONException e) {
            ModSelectionNotice.show(78, "无法读取当前模组选择或请求重启。\n" + file + "\n" + e, e);
        }
        return false;
    }

    public static void verify(List<?> selected) {
        var ids = new LinkedHashSet<String>();
        for (Object mod : selected) {
            String id = ((SelectedMod) mod).getId();
            if (!ids.add(id)) throw new IllegalStateException("游戏返回重复启用的模组: " + id);
        }
        Set<String> prepared = PreparedSources.enabledIds();
        if (ids.equals(prepared)) return;
        Path file = selectionFile();
        try {
            byte[] saved = Files.readAllBytes(file);
            Set<String> savedIds = parse(saved);
            if (!savedIds.equals(ids)) throw new IOException("已保存=" + savedIds + "，游戏选择=" + ids);
            requestRestart(prepared, ids, saved);
        } catch (IOException | JSONException e) {
            ModSelectionNotice.exit(78, "模组选择未能保存或请求重启失败。请检查模组配置的写权限。\n" + file + "\n" + e, e);
        }
    }

    private static Path selectionFile() {
        return Path.of(System.getProperty("com.fs.starfarer.settings.paths.mods"), "enabled_mods.json").toAbsolutePath();
    }

    private static Set<String> parse(byte[] bytes) throws IOException {
        JSONArray entries = new JSONObject(new String(bytes, StandardCharsets.UTF_8)).getJSONArray("enabledMods");
        var ids = new LinkedHashSet<String>();
        for (int i = 0; i < entries.length(); i++) {
            String id = entries.getString(i);
            if (!ids.add(id)) throw new IOException("重复 ID: " + id);
        }
        return ids;
    }

    private static void requestRestart(Set<String> prepared, Set<String> ids, byte[] saved) throws IOException {
        String request = System.getProperty("ssoptimizer.standalone.restart");
        if (request == null) throw new IOException("缺少启动器的模组重启请求路径");
        Files.write(Path.of(request), saved, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        var added = new LinkedHashSet<>(ids);
        var removed = new LinkedHashSet<String>();
        if (prepared != null) {
            added.removeAll(prepared);
            removed.addAll(prepared);
        }
        removed.removeAll(ids);
        ModSelectionNotice.exit(75, "模组选择已保存。启动器将按新选择重新准备游戏。\n启用: " + added + "\n停用: " + removed, null);
    }
}
