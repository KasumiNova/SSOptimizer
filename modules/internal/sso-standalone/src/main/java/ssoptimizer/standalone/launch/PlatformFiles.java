package ssoptimizer.standalone.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

record PlatformFiles(String id, Path gameNative, Path optimizerNative, Path lwjgl, Path lwjglUtil) {
    static PlatformFiles inspect(GameInstall game, Path home, Path cache) throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String platform;
        if (os.startsWith("windows")) platform = "windows";
        else if (os.equals("linux")) platform = "linux";
        else throw new IOException("不支持的操作系统：" + os);
        if (!arch.equals("amd64") && !arch.equals("x86_64")) throw new IOException("需要 x64 JVM，实际架构为 " + arch);
        Path nativeDir = game.core().resolve("native").resolve(platform);
        for (String f : platform.equals("windows") ? List.of("lwjgl64.dll", "OpenAL64.dll", "jinput-raw_64.dll", "jinput-dx8_64.dll")
                : List.of("liblwjgl64.so", "libopenal64.so", "libjinput-linux64.so")) GameInstall.required(nativeDir.resolve(f));
        Path sso = home.resolve("native").resolve(platform);
        for (String mod : List.of("render", "loading", "texcompress", "font", "ime")) {
            GameInstall.required(sso.resolve(System.mapLibraryName("ssoptimizer_" + mod)));
        }
        return new PlatformFiles(platform, nativeDir, sso, unseal(game.core().resolve("lwjgl.jar"), cache),
                GameInstall.required(game.core().resolve("lwjgl_util.jar")));
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

    private static Path unseal(Path original, Path cache) throws IOException {
        String inputHash = GameInstall.digest(original);
        if (!inputHash.equals("527d509f60132e5b2653c7fc0f8cf299d6f698f4a8013342bef47705dc57ed3f")) {
            throw new IOException("LWJGL 与英文/常规汉化 RC8 原版不符：" + original + " (" + inputHash + ")");
        }
        Path dir = cache.resolve("lwjgl-v1").resolve(inputHash);
        Path jar = dir.resolve("lwjgl.jar");
        Path hash = dir.resolve("output.sha256");
        InstancePaths.confined(cache, jar);
        InstancePaths.confined(cache, hash);
        Files.createDirectories(dir);
        if (Files.isRegularFile(jar) && Files.isRegularFile(hash) && GameInstall.digest(jar).equals(Files.readString(hash))) return jar;
        Path tmp = Files.createTempFile(dir, "lwjgl-", ".tmp");
        try (var input = new JarFile(original.toFile())) {
            Manifest manifest = input.getManifest();
            if (manifest == null) return original;
            manifest.getMainAttributes().remove(Attributes.Name.SEALED);
            manifest.getEntries().values().forEach(a -> a.remove(Attributes.Name.SEALED));
            try (var out = new JarOutputStream(Files.newOutputStream(tmp), manifest)) {
                var entries = input.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory() || entry.getName().equalsIgnoreCase(JarFile.MANIFEST_NAME)) continue;
                    if (entry.getName().matches("(?i)META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) throw new IOException("不支持重新打包已签名的 LWJGL：" + original);
                    var copy = new JarEntry(entry.getName()); copy.setTime(0); out.putNextEntry(copy);
                    try (var in = input.getInputStream(entry)) { in.transferTo(out); }
                    out.closeEntry();
                }
            }
            Files.move(tmp, jar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(hash, GameInstall.digest(jar));
            return jar;
        } finally { Files.deleteIfExists(tmp); }
    }
}
