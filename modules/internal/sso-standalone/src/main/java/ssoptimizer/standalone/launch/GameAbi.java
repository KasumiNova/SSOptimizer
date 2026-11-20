package ssoptimizer.standalone.launch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

record GameAbi(Properties groups) {
    static GameAbi read(Path home, String expected) throws IOException {
        Path file = home.resolve("abi.properties");
        Properties properties = Distribution.read(file);
        if (!expected.equals(properties.getProperty("abi")) || !"1".equals(properties.getProperty("rules"))) {
            throw new IOException("游戏 ABI 或净化规则版本不匹配：" + file);
        }
        return new GameAbi(properties);
    }

    List<String> archiveHashes(GameInstall game) throws IOException {
        var result = new ArrayList<String>();
        for (Path path : game.gameJars()) result.add(GameInstall.digest(GameInstall.required(path)));
        return result;
    }

    int group(String type, List<String> hashes) throws IOException {
        String key = type + ".count";
        int count;
        try { count = Integer.parseInt(groups.getProperty(key)); }
        catch (NumberFormatException e) { throw new IOException("无效的核心归档组数量：" + key, e); }
        if (count <= 0) throw new IOException("发行 ABI 未声明输入组：" + type);
        String actual = String.join(" ", hashes);
        for (int i = 0; i < count; i++) {
            if (actual.equals(groups.getProperty(type + "." + i))) return i;
        }
        return -1;
    }
}
