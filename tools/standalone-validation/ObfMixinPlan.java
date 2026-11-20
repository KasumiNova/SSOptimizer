package ssoptimizer.standalone.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public final class ObfMixinPlan {
    private ObfMixinPlan() { }

    public static void main(String[] args) throws Exception {
        Path stage = Path.of(args[0]);
        var game = GameInstall.inspect(Path.of(args[1]));
        Path out = Path.of(args[2]);
        var distribution = Distribution.load(stage);
        ReleaseLibraries.verify(stage);
        System.out.println(ArchiveProfile.verify(game, stage, distribution.abi()));
        var libraries = game.libraries(stage);
        var hierarchy = new ArrayList<>(libraries);
        for (String name : List.of("lwjgl.jar", "lwjgl_util.jar")) {
            hierarchy.add(GameInstall.required(game.core().resolve(name)));
        }
        var mounted = ArchiveProfile.prepare(stage, out.resolve("cache"), game.gameJars(), List.of(), hierarchy, Set.of());
        var prepared = new ArrayList<Path>();
        for (Path original : game.gameJars()) {
            Path output = mounted.get(original.toAbsolutePath().normalize());
            if (output == null) throw new IOException("缺少已净化的游戏归档 " + original);
            prepared.add(output);
        }
        Path payload = GameInstall.required(stage.resolve("payload.jar"));
        var plan = new Properties();
        plan.setProperty("namespace", "obf");
        plan.setProperty("profile", game.profile());
        plan.setProperty("payload", payload.toString());
        plan.setProperty("payload.source", payload.toString());
        plan.setProperty("enabled.count", "1");
        plan.setProperty("enabled.0", "ssoptimizer");
        writePaths(plan, "game", prepared);
        for (int i = 0; i < game.gameJars().size(); i++) plan.setProperty("game.source." + i, game.gameJars().get(i).toString());
        writePaths(plan, "library", libraries);
        writePaths(plan, "mod", List.of());
        try (var writer = Files.newBufferedWriter(out.resolve("launch.properties"))) { plan.store(writer, null); }
        try (var writer = Files.newBufferedWriter(out.resolve("inputs.tsv"))) {
            writer.write("kind\tsha256\tpath\n");
            for (Path jar : game.gameJars()) writer.write("game\t" + GameInstall.digest(jar) + "\t" + jar + "\n");
            writer.write("payload\t" + GameInstall.digest(payload) + "\t" + payload + "\n");
            for (Path jar : GameInstall.jars(stage.resolve("lib"))) {
                writer.write("bootstrap\t" + GameInstall.digest(jar) + "\t" + jar + "\n");
            }
            for (Path jar : hierarchy) writer.write("library\t" + GameInstall.digest(jar) + "\t" + jar + "\n");
        }
    }

    private static void writePaths(Properties plan, String key, List<Path> paths) {
        plan.setProperty(key + ".count", Integer.toString(paths.size()));
        for (int i = 0; i < paths.size(); i++) plan.setProperty(key + "." + i, paths.get(i).toString());
    }
}
