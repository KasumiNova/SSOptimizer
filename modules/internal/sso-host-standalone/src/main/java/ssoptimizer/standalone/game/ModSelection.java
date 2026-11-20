package ssoptimizer.standalone.game;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import ssoptimizer.standalone.host.PreparedSources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                if (!ids.equals(prepared)) requestRestart(prepared, ids);
            }
            ModSelectionNotice.show(78, "当前模组组合无法启动游戏。请在模组管理器中调整选择。\n" + problem, null);
        } catch (IOException | JSONException e) {
            ModSelectionNotice.show(78, "无法读取当前模组选择或请求重启。\n" + file + "\n" + e, e);
        }
        return false;
    }

    public static void restartIfChanged(List<?> selected) {
        var ids = new LinkedHashSet<String>();
        for (Object mod : selected) {
            String id = ((SelectedMod) mod).getId();
            ids.add(id);
        }
        Set<String> prepared = PreparedSources.enabledIds();
        if (ids.equals(prepared)) return;
        requestRestart(prepared, ids);
    }

    private static Path selectionFile() {
        return Path.of(System.getProperty("com.fs.starfarer.settings.paths.mods"), "enabled_mods.json").toAbsolutePath();
    }

    private static Set<String> parse(byte[] bytes) throws IOException {
        JSONArray entries = new JSONObject(new String(bytes, StandardCharsets.UTF_8)).getJSONArray("enabledMods");
        var ids = new LinkedHashSet<String>();
        for (int i = 0; i < entries.length(); i++) {
            String id = entries.getString(i);
            ids.add(id);
        }
        return ids;
    }

    private static void requestRestart(Set<String> prepared, Set<String> ids) {
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
