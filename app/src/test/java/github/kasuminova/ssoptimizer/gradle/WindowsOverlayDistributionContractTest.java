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
        Path readme = repoRoot.resolve("README.md");
        Path rootLauncher = repoRoot.resolve("tools/starsector-ssoptimizer.bat");
        Path internalLauncher = repoRoot.resolve("tools/starsector.bat");

        assertTrue(Files.exists(rootLauncher), "必须提供 Windows 根目录直接启动 bat");
        assertTrue(Files.exists(internalLauncher), "必须提供 Windows 内部启动 bat");

        String buildScriptText = Files.readString(buildScript);
        assertTrue(buildScriptText.contains("stageWindowsOverlay"), "必须提供 Windows overlay staging 任务");
        assertTrue(buildScriptText.contains("packageWindowsOverlayZip"), "必须提供 Windows overlay 打包任务");
        assertTrue(buildScriptText.contains("tools/starsector-ssoptimizer.bat"), "Windows overlay 包必须包含根目录直接启动 bat");
        assertTrue(buildScriptText.contains("tools/starsector.bat"), "Windows overlay 包必须包含内部启动 bat");
        assertFalse(buildScriptText.contains("tools/enable_starsector_exe_launch.ps1"), "Windows overlay 包不应继续包含旧安装脚本");
        assertFalse(buildScriptText.contains("tools/enable_starsector_exe_launch.bat"), "Windows overlay 包不应继续包含旧安装包装器");
        assertTrue(buildScriptText.contains("into(\"fonts\")"), "模组打包必须包含 fonts 目录");

        String readmeText = Files.readString(readme);
        assertTrue(readmeText.contains("starsector-ssoptimizer.bat"), "README 必须说明直接启动 bat 的用法");
        assertTrue(readmeText.contains("mods/ssoptimizer/fonts"), "README 必须说明 TTF 字体位于模组目录下");
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