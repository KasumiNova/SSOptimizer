package ssoptimizer.standalone.launch;

import github.kasuminova.ssoptimizer.standalone.obf.ObfArchives;
import ssoptimizer.standalone.mods.ModCatalog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ArchiveProfile {
    static final String FLAVOR = "obf", NAMESPACE = "obf", TOOL = "sso-obf-";
    static final String TOOL_ENTRY = "github/kasuminova/ssoptimizer/standalone/obf/ObfArchives.class";

    private ArchiveProfile() {}

    static String verify(GameInstall game, Path home, String abi) throws IOException {
        GameAbi profile = GameAbi.read(home, abi);
        List<String> hashes = profile.archiveHashes(game);
        int group = profile.group("inputs", hashes);
        if (group >= 0) return "游戏字节码 ABI：" + abi + "，净化规则：1，输入组：" + group;
        throw new IOException("安装的四个核心归档不匹配发行 ABI " + abi + "：" + game.gameJars() + "，SHA-256：" + hashes + "。已有 named 安装请使用 dev 入口。");
    }

    static Map<Path, Path> prepare(Path home, Path cache, List<Path> game, List<Path> mods, List<Path> libraries, Set<Path> named) throws IOException {
        return ObfArchives.prepare(cache.resolve("obf"), game, mods, libraries);
    }

    static Map<Path, Path> modPaths(Path cache, Map<ModCatalog.Archive, Path> archives) {
        Map<Path, Path> result = new LinkedHashMap<>();
        archives.forEach((archive, output) -> result.put(archive.path(), output));
        return result;
    }

    static Path importSave(GameInstall game, Path home, Path instance, Path source, String slot, String profile) throws IOException {
        throw new IOException("obf 正式版不支持 import-save 的 obf 到 named 转换。请在匹配源存档 ABI 的开发环境中迁移到新槽。");
    }
}
