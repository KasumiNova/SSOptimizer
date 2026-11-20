package ssoptimizer.standalone.launch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

record Distribution(String abi) {
    static Distribution load(Path home) throws IOException {
        Path file = home.resolve("distribution.properties");
        Properties properties = read(file);
        String abi = properties.getProperty("abi");
        if (!ArchiveProfile.FLAVOR.equals(properties.getProperty("flavor"))
                || !ArchiveProfile.NAMESPACE.equals(properties.getProperty("namespace"))
                || abi == null || !abi.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IOException("发行配置与 " + ArchiveProfile.FLAVOR + " 启动器不匹配：" + file);
        }
        return new Distribution(abi);
    }

    static Properties read(Path file) throws IOException {
        Properties result = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { result.load(reader); }
        return result;
    }

    void bindInstance(Path instance) throws IOException {
        Path marker = instance.resolve(".standalone-abi");
        InstancePaths.confined(instance, marker);
        String expected = ArchiveProfile.FLAVOR + ":" + abi;
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            String actual = Files.readString(marker).strip();
            if (!actual.equals(expected)) throw new IOException("实例已绑定 " + actual + "，请为 " + expected + " 指定另一实例目录：" + instance);
        } else {
            if (Files.exists(instance.resolve(".standalone-layout")) || Files.exists(instance.resolve("cache/launch.properties"))
                    || Files.exists(instance.resolve("saves"))) {
                throw new IOException("该实例已有未标注 ABI 的数据，请保留原实例并为 " + expected + " 指定新目录：" + instance);
            }
            Files.writeString(marker, expected + "\n", StandardOpenOption.CREATE_NEW);
        }
    }
}
