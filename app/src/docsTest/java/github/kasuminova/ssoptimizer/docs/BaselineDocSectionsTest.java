package github.kasuminova.ssoptimizer.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineDocSectionsTest {

    static final Path ROOT     = Path.of(System.getProperty("project.rootDir"));
    static final Path BASELINE = ROOT.resolve("docs/design/dev-environment-baseline-implementation.md");

    static final List<String> REQUIRED_SECTIONS = List.of(
            "# SSOptimizer 开发环境基线实现文档",
            "## 1. 目标与范围",
            "## 2. Minecraft 风格环境标准对齐",
            "## 3. 工具链与版本基线",
            "## 4. 运行配置（Run Profiles）",
            "## 5. IDE 与调试体验基线",
            "## 6. 混淆映射（Mapping）工作流",
            "## 7. Mixin/ASM 注入开发规范",
            "## 8. 测试与质量门",
            "## 9. 故障排查与回滚",
            "## 10. 团队协作规范"
    );

    @Test
    void baselineDocContainsAllRequiredSections() throws IOException {
        assertTrue(Files.exists(BASELINE), "Missing: docs/design/dev-environment-baseline-implementation.md");
        String content = Files.readString(BASELINE);
        for (String section : REQUIRED_SECTIONS) {
            assertTrue(content.contains(section), "Missing section: " + section);
        }
    }
}
