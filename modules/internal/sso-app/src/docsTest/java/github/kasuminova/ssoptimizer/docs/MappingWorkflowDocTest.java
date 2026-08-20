package github.kasuminova.ssoptimizer.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingWorkflowDocTest {

    static final Path ROOT        = Path.of(System.getProperty("project.rootDir"));
    static final Path MAPPING_DOC = ROOT.resolve("docs/design/dev-environment-mapping-workflow.md");

    static final List<String> REQUIRED_SECTIONS = List.of(
            "# SSOptimizer 渲染链路 Mapping 工作流",
            "## 命名规范",
            "## 证据链规范",
            "## 版本化与审查"
    );

    @Test
    void mappingDocContainsAllRequiredSections() throws IOException {
        assertTrue(Files.exists(MAPPING_DOC), "Missing: docs/design/dev-environment-mapping-workflow.md");
        String content = Files.readString(MAPPING_DOC);
        for (String section : REQUIRED_SECTIONS) {
            assertTrue(content.contains(section), "Missing section: " + section);
        }
    }
}
