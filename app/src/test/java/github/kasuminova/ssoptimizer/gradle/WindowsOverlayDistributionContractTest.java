package github.kasuminova.ssoptimizer.gradle;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Windows 覆盖安装分发契约测试。
 * <p>
 * 该测试约束仓库必须持续提供面向 Windows 的直接启动 bat 与对应的 overlay 打包入口。
 */
class WindowsOverlayDistributionContractTest {

    @Test
    void repositoryKeepsWindowsOverlayInstallerArtifacts() throws Exception {
        Path repoRoot = MappingTaskContractTest.repositoryRoot();
        Path buildScript = repoRoot.resolve("build.gradle.kts");
        Path releaseWorkflow = repoRoot.resolve(".github/workflows/release.yml");
        Path readme = repoRoot.resolve("README.md");
        Path log4jConfig = repoRoot.resolve("log4j.properties");
        Path rootLauncher = repoRoot.resolve("tools/starsector-ssoptimizer.bat");
        Path internalLauncher = repoRoot.resolve("tools/starsector.bat");

        assertTrue(Files.exists(rootLauncher), "必须提供 Windows 根目录直接启动 bat");
        assertTrue(Files.exists(internalLauncher), "必须提供 Windows 内部启动 bat");
        assertTrue(Files.exists(log4jConfig), "必须提供随安装包分发的 log4j.properties");

        String buildScriptText = Files.readString(buildScript);
        assertTrue(buildScriptText.contains("stageWindowsOverlay"), "必须提供 Windows overlay staging 任务");
        assertTrue(buildScriptText.contains("packageWindowsOverlayZip"), "必须提供 Windows overlay 打包任务");
        assertTrue(buildScriptText.contains("into(\"mods/$modId\")"), "Windows overlay 必须将模组放在根 mods 目录");
        assertFalse(buildScriptText.contains("into(\"starsector-core/mods/$modId\")"), "Windows overlay 不应把模组错误打到 starsector-core/mods");
        assertTrue(buildScriptText.contains("tools/starsector-ssoptimizer.bat"), "Windows overlay 包必须包含根目录直接启动 bat");
        assertTrue(buildScriptText.contains("tools/starsector.bat"), "Windows overlay 包必须包含内部启动 bat");
        assertFalse(buildScriptText.contains("tools/enable_starsector_exe_launch.ps1"), "Windows overlay 包不应继续包含旧安装脚本");
        assertFalse(buildScriptText.contains("tools/enable_starsector_exe_launch.bat"), "Windows overlay 包不应继续包含旧安装包装器");
        assertTrue(buildScriptText.contains("into(\"fonts\")"), "模组打包必须包含 fonts 目录");
        assertTrue(buildScriptText.contains("from(log4jConfigFile)"), "overlay 打包必须包含 log4j.properties");

        String releaseWorkflowText = Files.readString(releaseWorkflow);
        assertTrue(releaseWorkflowText.contains("tools/starsector-ssoptimizer.bat"), "Release workflow 必须包含 Windows 根目录直接启动 bat");
        assertTrue(releaseWorkflowText.contains("WINDOWS_MOD_STAGE=\"${WINDOWS_STAGE}/mods/${MOD_ID}\""), "Release workflow 必须将 Windows 模组放在根 mods 目录");
        assertTrue(releaseWorkflowText.contains("cp game-fonts/ttf/* \"${LINUX_MOD_STAGE}/fonts/\""), "Release workflow 必须将 Linux TTF 打包到模组 fonts 目录");
        assertTrue(releaseWorkflowText.contains("cp game-fonts/ttf/* \"${WINDOWS_MOD_STAGE}/fonts/\""), "Release workflow 必须将 Windows TTF 打包到模组 fonts 目录");
        assertTrue(releaseWorkflowText.contains("cp game-fonts/fnt/* \"${LINUX_STAGE}/graphics/fonts/\""), "Release workflow 必须将 Linux FNT 打包到平台根 graphics/fonts");
        assertTrue(releaseWorkflowText.contains("cp game-fonts/fnt/* \"${WINDOWS_STAGE}/starsector-core/graphics/fonts/\""), "Release workflow 必须将 Windows FNT 打包到平台根 graphics/fonts");
        assertTrue(releaseWorkflowText.contains("cp log4j.properties \"${LINUX_STAGE}/log4j.properties\""), "Release workflow 必须为 Linux 安装包写入 log4j.properties");
        assertTrue(releaseWorkflowText.contains("cp log4j.properties \"${WINDOWS_STAGE}/starsector-core/log4j.properties\""), "Release workflow 必须为 Windows 启动目录写入 log4j.properties");
        assertFalse(releaseWorkflowText.contains("enable_starsector_exe_launch.ps1"), "Release workflow 不应继续引用旧安装脚本");
        assertFalse(releaseWorkflowText.contains("enable_starsector_exe_launch.bat"), "Release workflow 不应继续引用旧安装包装器");

        String readmeText = Files.readString(readme);
        assertTrue(readmeText.contains("starsector-ssoptimizer.bat"), "README 必须说明直接启动 bat 的用法");
        assertTrue(readmeText.contains("mods/ssoptimizer/fonts"), "README 必须说明 TTF 字体位于模组目录下");
        assertTrue(readmeText.contains("解压后应得到 `mods/ssoptimizer/`"), "README 必须说明 Windows 模组位于根 mods 目录");
        assertFalse(readmeText.contains("starsector-core/mods/ssoptimizer"), "README 不应继续声明 Windows 模组位于 starsector-core/mods");
        assertTrue(readmeText.contains("log4j.properties"), "README 必须说明安装包携带 log4j 配置文件");
        assertFalse(readmeText.contains("enable_starsector_exe_launch.bat"), "README 不应继续引用旧安装脚本");
        assertTrue(readmeText.contains("starsector-ssoptimizer.bat"), "README 必须说明独立启动器的用法");
        assertTrue(readmeText.contains("packageWindowsOverlayZip"), "README 必须说明 Windows overlay 打包入口");
    }

    @Test
    void windowsBatchWrappersKeepCrLfLineEndings() throws Exception {
        Path repoRoot = MappingTaskContractTest.repositoryRoot();
        List<Path> batchFiles = List.of(
            repoRoot.resolve("tools/starsector-ssoptimizer.bat"),
                repoRoot.resolve("tools/starsector.bat")
        );

        for (Path batchFile : batchFiles) {
            assertTrue(Files.exists(batchFile), () -> "缺少 Windows bat 脚本: " + batchFile);
            byte[] content = Files.readAllBytes(batchFile);
            assertTrue(containsCrLfLineEnding(content), () -> "Windows bat 脚本必须使用 CRLF 换行: " + batchFile);
            assertTrue(!containsBareLf(content), () -> "Windows bat 脚本不能包含裸 LF 换行: " + batchFile);
        }
    }

    @Test
    void windowsLauncherParametersStayAlignedWithCurrentBaseline() throws Exception {
        Path repoRoot = MappingTaskContractTest.repositoryRoot();
        Path windowsLauncher = repoRoot.resolve("tools/starsector.bat");
        String content = Files.readString(windowsLauncher);

        assertTrue(content.contains("-XX:+UseZGC"), "Windows 启动脚本必须使用当前基线 GC 参数");
        assertTrue(content.contains("-Djdk.xml.maxElementDepth=10000"), "Windows 启动脚本必须包含 XML 深度保护参数");
        assertTrue(content.contains("-Dlog4j.configuration=file:./log4j.properties"), "Windows 启动脚本必须显式指定 log4j 配置");
        assertTrue(content.contains("-Djava.library.path=./native/windows"), "Windows 启动脚本必须指向 Windows 原生库目录");
        assertTrue(content.contains("-Dcom.fs.starfarer.settings.windows=true"), "Windows 启动脚本必须显式标记 Windows 平台");
        assertTrue(content.contains("-Xms4096m"), "Windows 启动脚本必须与当前基线对齐初始堆大小");
        assertTrue(content.contains("-Xmx4096m"), "Windows 启动脚本必须与当前基线对齐最大堆大小");
        assertFalse(content.contains("-XX:+UseShenandoahGC"), "Windows 启动脚本不应继续使用旧的 Shenandoah 参数");
        assertFalse(content.contains("-XX:ShenandoahGCMode=iu"), "Windows 启动脚本不应继续包含旧的 Shenandoah 模式参数");
    }

    private static boolean containsCrLfLineEnding(byte[] content) {
        for (int index = 1; index < content.length; index++) {
            if (content[index - 1] == '\r' && content[index] == '\n') {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBareLf(byte[] content) {
        for (int index = 0; index < content.length; index++) {
            if (content[index] == '\n' && (index == 0 || content[index - 1] != '\r')) {
                return true;
            }
        }
        return false;
    }
}