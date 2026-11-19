package ssoptimizer.standalone.instance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ssoptimizer.standalone.mods.ModCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static ssoptimizer.standalone.instance.ModCopies.Change.*;

class ModCopiesTest {
    @TempDir Path temp;

    @Test void firstInstallationPreservesExplicitSelectionBytesAndLaterAbsence() throws Exception {
        Path game = Files.createDirectory(temp.resolve("game"));
        Path gameMods = Files.createDirectory(game.resolve("mods"));
        Path version = Files.createDirectory(temp.resolve("version"));
        Path template = Files.createDirectory(version.resolve("mod"));
        Files.writeString(template.resolve("mod_info.json"), "{\"id\":\"ssoptimizer\"}");
        Files.writeString(version.resolve("payload.jar"), "fixture payload");
        Path instance = temp.resolve("instance");
        Path installedSelection = instance.resolve("mods/enabled_mods.json");
        Path sourceSelection = gameMods.resolve("enabled_mods.json");
        String selection = "{\n  \"enabledMods\": [\"ordinary\", \"ordinary\"]\n}\n";
        Files.writeString(sourceSelection, selection);
        Path ordinary = Files.createDirectory(gameMods.resolve("ordinary"));
        Files.writeString(ordinary.resolve("mod_info.json"), "corrupted metadata");

        InstanceLayout.prepare(game, version, instance);
        assertEquals(selection, Files.readString(installedSelection));
        assertEquals("corrupted metadata", Files.readString(instance.resolve("mods/ordinary/mod_info.json")));
        Files.delete(installedSelection);
        Path otherGame = Files.createDirectory(temp.resolve("other-game"));
        InstanceLayout.prepare(otherGame, version, instance);
        assertFalse(Files.exists(installedSelection));
        assertEquals("corrupted metadata", Files.readString(instance.resolve("mods/ordinary/mod_info.json")));

        assertEquals(selection, Files.readString(sourceSelection));
        Files.delete(sourceSelection);
        Path clean = temp.resolve("clean");
        InstanceLayout.prepare(otherGame, version, clean);
        assertEquals(List.of("ssoptimizer"), ModCatalog.enabledIds(clean.resolve("mods")));

        Path installedOptimizer = Files.createDirectory(gameMods.resolve("old-optimizer"));
        Files.writeString(installedOptimizer.resolve("mod_info.json"), "{\"id\":\"ssoptimizer\"}");
        Path disabled = temp.resolve("disabled");
        InstanceLayout.prepare(game, version, disabled);
        assertFalse(Files.exists(disabled.resolve("mods/enabled_mods.json")));
    }

    @Test void localStateAndPublisherFilesUseDifferentOwnershipRules() {
        assertEquals(PRESERVE, ModCopies.decide("-", "player", "player", false));
        assertEquals(PRESERVE, ModCopies.decide("base", "player", "upstream", false));
        assertEquals(PRESERVE, ModCopies.decide("base", "player", "-", false));
        assertEquals(PRESERVE, ModCopies.decide("base", "-", "upstream", false));
        assertEquals(UPDATE, ModCopies.decide("base", "base", "-", false));
        assertEquals(UPDATE, ModCopies.decide("base", "base", "upstream", false));
        assertEquals(PRESERVE, ModCopies.decide("directory", "directory", "upstream", false));
        assertEquals(UNOWNED, ModCopies.decide("-", "player", "player", true));
        assertEquals(CONFLICT, ModCopies.decide("base", "player", "upstream", true));
        assertEquals(KEEP, ModCopies.decide("base", "upstream", "upstream", true));
    }

    @Test void ownershipRecordsPreserveLocalEditsAndDeletionAcrossUpstreamRemovalAndRestore() throws Exception {
        Path game = Files.createDirectory(temp.resolve("game"));
        Path upstream = Files.createDirectory(game.resolve("ordinary"));
        Path mods = Files.createDirectory(temp.resolve("mods"));
        Path state = Files.createDirectory(temp.resolve("layout"));
        Path registry = state.resolve("installed.properties");
        Path local = mods.resolve("ordinary");
        Path edited = Files.writeString(upstream.resolve("settings.json"), "old settings");
        Path deleted = Files.writeString(upstream.resolve("removed.csv"), "old entries");
        Path revised = Files.writeString(upstream.resolve("update.csv"), "old data");
        var sources = Map.of("ordinary/", upstream, "ordinary/settings.json", edited,
                "ordinary/removed.csv", deleted, "ordinary/update.csv", revised);

        ModCopies.update(state, mods, game, sources, ModCopies.read(registry), null);
        Files.writeString(local.resolve("settings.json"), "player settings");
        Files.delete(local.resolve("removed.csv"));
        Files.writeString(local.resolve("player.json"), "instance only");
        Files.writeString(edited, "upstream settings");
        Files.writeString(deleted, "upstream entries");
        Files.writeString(revised, "upstream data");

        ModCopies.update(state, mods, game, sources, ModCopies.read(registry), null);
        assertEquals("upstream data", Files.readString(local.resolve("update.csv")));
        ModCopies.update(state, mods, game, Map.of(), ModCopies.read(registry), null);
        assertFalse(Files.exists(local.resolve("update.csv")));
        ModCopies.update(state, mods, game, sources, ModCopies.read(registry), null);

        assertEquals("player settings", Files.readString(local.resolve("settings.json")));
        assertFalse(Files.exists(local.resolve("removed.csv")));
        assertEquals("instance only", Files.readString(local.resolve("player.json")));
        assertEquals("upstream settings", Files.readString(edited));
        assertEquals("upstream entries", Files.readString(deleted));
    }

    @Test void officialPayloadIsStrictWhileBundledFontsHonorPlayerEdits() throws Exception {
        Path game = Files.createDirectory(temp.resolve("game"));
        Path mods = Files.createDirectory(temp.resolve("mods"));
        Path state = Files.createDirectory(temp.resolve("layout"));
        Path registry = state.resolve("installed.properties");
        Path font = Files.writeString(game.resolve("font.ttf"), "release font");
        Path payload = Files.writeString(game.resolve("payload.jar"), "release binary");
        var sources = Map.of("ssoptimizer/", game, "ssoptimizer/fonts/", game, "ssoptimizer/fonts/font.ttf", font,
                "ssoptimizer/jars/", game, "ssoptimizer/jars/payload.jar", payload);

        ModCopies.update(state, mods, game, sources, ModCopies.read(registry), null);
        Path installedFont = mods.resolve("ssoptimizer/fonts/font.ttf");
        Path installedPayload = mods.resolve("ssoptimizer/jars/payload.jar");
        Files.writeString(installedFont, "custom font");
        Files.writeString(font, "updated release font");
        Files.writeString(payload, "updated release binary");
        ModCopies.update(state, mods, game, sources, ModCopies.read(registry), null);
        assertEquals("custom font", Files.readString(installedFont));
        assertEquals("updated release binary", Files.readString(installedPayload));

        Files.writeString(installedPayload, "foreign binary");
        assertThrows(IOException.class, () -> ModCopies.update(state, mods, game, sources, ModCopies.read(registry), null));
        assertEquals("foreign binary", Files.readString(installedPayload));
    }
}
