package github.kasuminova.ssoptimizer.gradle;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Windows 覆盖安装分发契约测试。
 * <p>
 * 该测试约束仓库必须持续提供 Windows overlay 打包入口，且发布产物采用
 * NanoForge coremod（mods/coremods）+ 游戏原生 mod（mods/ssoptimizer）双轨布局。
 */
class WindowsOverlayDistributionContractTest {

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (current.resolve("settings.gradle.kts").toFile().isFile()
                    && current.resolve("build.gradle.kts").toFile().isFile()
                    && current.resolve("modules/internal/sso-app").toFile().isDirectory()) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位 SSOptimizer 仓库根目录");
    }

    @Test
    void repositoryKeepsWindowsOverlayInstallerArtifacts() throws Exception {
        Path repoRoot = repositoryRoot();
        Path buildScript = repoRoot.resolve("build.gradle.kts");
        Path releaseWorkflow = repoRoot.resolve(".github/workflows/release.yml");
        Path readme = repoRoot.resolve("README.md");
        Path log4jConfig = repoRoot.resolve("log4j.properties");

        assertTrue(Files.exists(log4jConfig), "必须提供随安装包分发的 log4j.properties");

        String buildScriptText = Files.readString(buildScript);
        assertTrue(buildScriptText.contains("stageWindowsOverlay"), "必须提供 Windows overlay staging 任务");
        assertTrue(buildScriptText.contains("packageWindowsOverlayZip"), "必须提供 Windows overlay 打包任务");
        assertTrue(buildScriptText.contains("into(\"mods/$modId\")"), "Windows overlay 必须将模组放在根 mods 目录");
        assertFalse(buildScriptText.contains("into(\"starsector-core/mods/$modId\")"), "Windows overlay 不应把模组错误打到 starsector-core/mods");
        assertTrue(buildScriptText.contains("into(\"mods/coremods\")"), "Windows overlay 必须包含 NanoForge coremod 入口 jar");
        assertTrue(buildScriptText.contains("into(\"fonts\")"), "模组打包必须包含 fonts 目录");
        assertTrue(buildScriptText.contains("from(log4jConfigFile)"), "overlay 打包必须包含 log4j.properties");

        String releaseWorkflowText = Files.readString(releaseWorkflow);
        assertTrue(releaseWorkflowText.contains("mods/coremods"), "Release workflow 必须包含 NanoForge coremod 入口 jar");
        assertTrue(releaseWorkflowText.contains("WINDOWS_MOD_STAGE=\"${WINDOWS_STAGE}/mods/${MOD_ID}\""), "Release workflow 必须将 Windows 模组放在根 mods 目录");
        assertTrue(releaseWorkflowText.contains("cp game-fonts/ttf/* \"${LINUX_MOD_STAGE}/fonts/\""), "Release workflow 必须将 Linux TTF 打包到模组 fonts 目录");
        assertTrue(releaseWorkflowText.contains("cp game-fonts/ttf/* \"${WINDOWS_MOD_STAGE}/fonts/\""), "Release workflow 必须将 Windows TTF 打包到模组 fonts 目录");
        assertTrue(releaseWorkflowText.contains("cp log4j.properties \"${LINUX_STAGE}/log4j.properties\""), "Release workflow 必须为 Linux 安装包写入 log4j.properties");
        assertTrue(releaseWorkflowText.contains("cp log4j.properties \"${WINDOWS_STAGE}/starsector-core/log4j.properties\""), "Release workflow 必须为 Windows 启动目录写入 log4j.properties");

        String readmeText = Files.readString(readme);
        assertTrue(readmeText.contains("mods/coremods/SSOptimizer.jar"), "README 必须说明 coremod 入口 jar 的安装位置");
        assertTrue(readmeText.contains("mods/ssoptimizer/fonts"), "README 必须说明 TTF 字体位于模组目录下");
        assertTrue(readmeText.contains("解压后应得到 `mods/ssoptimizer/`"), "README 必须说明 Windows 模组位于根 mods 目录");
        assertFalse(readmeText.contains("starsector-core/mods/ssoptimizer"), "README 不应继续声明 Windows 模组位于 starsector-core/mods");
        assertTrue(readmeText.contains("log4j.properties"), "README 必须说明安装包携带 log4j 配置文件");
        assertTrue(readmeText.contains("packageWindowsOverlayZip"), "README 必须说明 Windows overlay 打包入口");
    }
}
