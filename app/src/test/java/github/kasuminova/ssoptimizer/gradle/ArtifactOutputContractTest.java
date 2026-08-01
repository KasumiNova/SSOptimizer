package github.kasuminova.ssoptimizer.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gradle 产物输出契约测试。
 * <p>
 * 该测试定义 remapped workspace 的落盘位置，确保 dev 工作区工具（remapToNamed）
 * 持续产出 named 游戏 jar 与可挂载的工作区布局。
 */
class ArtifactOutputContractTest {

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (current.resolve("settings.gradle.kts").toFile().isFile()
                    && current.resolve("build.gradle.kts").toFile().isFile()
                    && current.resolve("app").toFile().isDirectory()) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位 SSOptimizer 仓库根目录");
    }

    @Test
    void producesRemappedWorkspaceOutputs() throws Exception {
        Path repoRoot = repositoryRoot();
        Path buildDir = repoRoot.resolve("build");

        GradleRunner.create()
                    .withProjectDir(repoRoot.toFile())
                    .withArguments("prepareDeobfWorkspace", "remapToNamed", "-Pstarsector.platform=windows", "--console=plain")
                    .forwardOutput()
                    .build();

        assertTrue(Files.exists(buildDir.resolve("remapped-workspace")), "应生成 remapped workspace 目录");
        assertTrue(Files.exists(buildDir.resolve("named-game-jars")), "应生成 named game jars 目录");
        assertTrue(Files.exists(buildDir.resolve("named-game-jars/windows")), "应生成平台隔离的 named game jars 目录");
        assertTrue(Files.exists(buildDir.resolve("remapped-workspace/game-jars/named")), "workspace 中应包含 named game jars");

        try (Stream<Path> jars = Files.list(buildDir.resolve("named-game-jars/windows"))) {
            assertTrue(jars.anyMatch(path -> path.getFileName().toString().endsWith(".jar")), "named game jars 目录中应至少有一个 remap 后的 jar");
        }
    }
}