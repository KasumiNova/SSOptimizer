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

    public static void verify(List<?> selected) {
        var ids = new LinkedHashSet<String>();
        for (Object mod : selected) {
            String id = ((SelectedMod) mod).getId();
            if (!ids.add(id)) throw new IllegalStateException("游戏返回重复启用的模组: " + id);
        }
        Set<String> prepared = PreparedSources.enabledIds();
        if (prepared.equals(ids)) return;
        Path file = Path.of(System.getProperty("com.fs.starfarer.settings.paths.mods"), "enabled_mods.json").toAbsolutePath();
        try {
            JSONArray entries = new JSONObject(Files.readString(file, StandardCharsets.UTF_8)).getJSONArray("enabledMods");
            var saved = new LinkedHashSet<String>();
            for (int i = 0; i < entries.length(); i++) {
                String id = entries.getString(i);
                if (!saved.add(id)) throw new IOException("重复 ID: " + id);
            }
            if (!saved.equals(ids)) throw new IOException("已保存=" + saved + "，游戏选择=" + ids);
        } catch (IOException | JSONException e) {
            ModSelectionNotice.exit(78, "模组选择未能保存。请检查实例模组配置的写权限后重新启动。\n" + file + "\n" + e, e);
            return;
        }
        if (!ids.contains("ssoptimizer")) {
            ModSelectionNotice.exit(78, "独立版需要启用 SSOptimizer。请在实例的 enabled_mods.json 中恢复 ssoptimizer 后重新启动，或使用原版游戏入口。\n" + file, null);
            return;
        }
        var added = new LinkedHashSet<>(ids);
        added.removeAll(prepared);
        var removed = new LinkedHashSet<>(prepared);
        removed.removeAll(ids);
        ModSelectionNotice.exit(75, "模组选择已保存。启动器将按新选择重新准备并启动游戏。\n启用: " + added + "\n停用: " + removed, null);
    }
}
