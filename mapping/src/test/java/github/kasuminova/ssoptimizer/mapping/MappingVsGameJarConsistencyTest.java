package github.kasuminova.ssoptimizer.mapping;

import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 映射表与游戏 jar 字节码的一致性测试。
 * <p>
 * 该测试以 {@code game-jars/{platform}/} 下入库的游戏 jar 为唯一事实源，
 * 断言 {@code ssoptimizer-linux.tiny} / {@code ssoptimizer-windows.tiny} 中每条类、字段、方法映射
 * 的混淆名与描述符都真实存在于对应平台的 jar 中，防止映射表随游戏版本漂移后无人察觉。
 * 两个平台与宿主 OS 无关地全部校验，单平台 CI 也能抓住另一平台的漂移。
 * 校验逻辑见 {@link GameJarConsistency}。
 */
class MappingVsGameJarConsistencyTest {
    @Test
    void linuxMappingMatchesLinuxGameJars() throws IOException {
        GameJarConsistency.assertConsistency(MappingPlatform.LINUX, TinyV2MappingRepository.loadForPlatform(MappingPlatform.LINUX));
    }

    @Test
    void windowsMappingMatchesWindowsGameJars() throws IOException {
        GameJarConsistency.assertConsistency(MappingPlatform.WINDOWS, TinyV2MappingRepository.loadForPlatform(MappingPlatform.WINDOWS));
    }
}
