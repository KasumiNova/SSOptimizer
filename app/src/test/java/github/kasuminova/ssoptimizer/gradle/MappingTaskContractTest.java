package github.kasuminova.ssoptimizer.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gradle mapping 任务契约测试。
 * <p>
 * 该测试定义 mapped / reobf 工作流必须暴露的任务名称，避免后续重构时把开发、发布
 * 和 remapped workspace 的入口悄悄改名。
 */
public class MappingTaskContractTest {

    private static void assertTaskExists(Path repoRoot, String taskName) {
        String output = GradleRunner.create()
                                    .withProjectDir(repoRoot.toFile())
                                    .withArguments("help", "--task", taskName, "--console=plain")
                                    .forwardOutput()
                                    .build()
                                    .getOutput();

        assertTrue(output.contains(taskName), () -> "缺少 Gradle mapping 任务: " + taskName + "\n" + output);
    }

    public static Path repositoryRoot() {
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
    void declaresExpectedMappingTasks() {
        Path repoRoot = repositoryRoot();

        assertTaskExists(repoRoot, "prepareDeobfWorkspace");
        assertTaskExists(repoRoot, "remapToNamed");
        assertTaskExists(repoRoot, "jarMapped");
        assertTaskExists(repoRoot, "jarReobf");
        assertTaskExists(repoRoot, "installDevMod");
    }
}