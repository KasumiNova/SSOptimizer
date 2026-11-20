package ssoptimizer.standalone.launch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

record PlatformFiles(String id, Path gameNative, Path optimizerNative, Path lwjgl, Path lwjglUtil) {
    static PlatformFiles inspect(GameInstall game, Path home) throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String platform;
        if (os.startsWith("windows")) platform = "windows";
        else if (os.equals("linux")) platform = "linux";
        else throw new IOException("不支持的操作系统：" + os);
        if (!arch.equals("amd64") && !arch.equals("x86_64")) throw new IOException("需要 x64 JVM，实际架构为 " + arch);
        Path nativeDir = game.core().resolve("native").resolve(platform);
        Path sso = home.resolve("native").resolve(platform);
        Path lwjgl = game.core().resolve("lwjgl.jar");
        return new PlatformFiles(platform, nativeDir, sso, lwjgl,
                game.core().resolve("lwjgl_util.jar"));
    }

    List<String> arguments() {
        var args = new ArrayList<String>();
        args.add("-Djava.library.path=" + gameNative + java.io.File.pathSeparator + optimizerNative);
        args.add("-Dorg.lwjgl.librarypath=" + gameNative);
        args.add("-Dnet.java.games.input.librarypath=" + gameNative);
        if (id.equals("linux")) args.add("-Dcom.fs.starfarer.settings.linux=true");
        for (String module : List.of("render", "loading", "texcompress", "font", "ime")) {
            args.add("-Dssoptimizer.native.path." + module + "=" + optimizerNative.resolve(System.mapLibraryName("ssoptimizer_" + module)));
        }
        return args;
    }
}
