package github.kasuminova.ssoptimizer.gradle;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Windows 覆盖安装分发契约测试。
 * <p>
 * 该测试约束仓库必须持续提供面向 Windows 的 vmparams 补丁脚本，以及对应的 overlay 打包入口，
 * 以便终端用户在保留 starsector.exe 启动方式的前提下启用 SSOptimizer。
 */
class WindowsOverlayDistributionContractTest {

    @Test
    void repositoryKeepsWindowsOverlayInstallerArtifacts() throws Exception {
        Path repoRoot = MappingTaskContractTest.repositoryRoot();
        Path buildScript = repoRoot.resolve("build.gradle.kts");
        Path readme = repoRoot.resolve("README.md");
        Path installerScript = repoRoot.resolve("tools/enable_starsector_exe_launch.ps1");

        assertTrue(Files.exists(installerScript), "必须提供 Windows starsector.exe 启动补丁脚本");

        String buildScriptText = Files.readString(buildScript);
        assertTrue(buildScriptText.contains("stageWindowsOverlay"), "必须提供 Windows overlay staging 任务");
        assertTrue(buildScriptText.contains("packageWindowsOverlayZip"), "必须提供 Windows overlay 打包任务");
        assertTrue(buildScriptText.contains("tools/enable_starsector_exe_launch.ps1"), "Windows overlay 包必须包含 vmparams 补丁脚本");

        String readmeText = Files.readString(readme);
        assertTrue(readmeText.contains("enable_starsector_exe_launch.ps1"), "README 必须说明 starsector.exe 启动补丁脚本的用法");
        assertTrue(readmeText.contains("packageWindowsOverlayZip"), "README 必须说明 Windows overlay 打包入口");
    }
}