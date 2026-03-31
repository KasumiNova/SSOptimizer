package github.kasuminova.ssoptimizer.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadmeLinksTest {

    static final Path ROOT   = Path.of(System.getProperty("project.rootDir"));
    static final Path README = ROOT.resolve("README.md");

    static final List<String> REQUIRED_LINKS = List.of(
            "docs/design/dev-environment-baseline-implementation.md",
            "docs/design/dev-environment-onboarding-checklist.md",
            "docs/design/dev-environment-mapping-workflow.md",
            "docs/design/dev-environment-run-profiles.md",
            "docs/design/dev-environment-troubleshooting.md"
    );

    @Test
    void readmeContainsAllDocLinks() throws IOException {
        assertTrue(Files.exists(README), "README.md must exist");
        String content = Files.readString(README);
        for (String link : REQUIRED_LINKS) {
            assertTrue(content.contains(link), "README missing link: " + link);
        }
    }
}
