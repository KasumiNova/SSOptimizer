#!/usr/bin/env python3
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]

REQUIRED_FILES = {
    "docs/design/dev-environment-baseline-implementation.md": [
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
        "## 10. 团队协作规范",
    ],
    "docs/design/dev-environment-onboarding-checklist.md": [
        "# SSOptimizer 新成员上手清单",
        "## Day 0 环境准备",
        "## Day 1 运行验证",
        "## Day 2 Mapping 与注入演练",
    ],
    "docs/design/dev-environment-mapping-workflow.md": [
        "# SSOptimizer 渲染链路 Mapping 工作流",
        "## 命名规范",
        "## 证据链规范",
        "## 版本化与审查",
    ],
    "docs/design/dev-environment-run-profiles.md": [
        "# SSOptimizer 开发运行档（Run Profiles）",
        "## Dev Profile",
        "## Safe Profile",
        "## Trace Profile",
    ],
    "docs/design/dev-environment-troubleshooting.md": [
        "# SSOptimizer 开发环境故障排查手册",
        "## 常见错误索引",
        "## 快速恢复流程",
    ],
}

REQUIRED_SNIPPETS = [
    "./gradlew doctor",
    "./gradlew :app:test",
    "./gradlew :app:run",
    "./gradlew docsCheck",
]

MC_STYLE_KEYWORDS = [
    "runClient 等价流程",
    "Mappings 生命周期",
    "Mixin 调试开关",
    "DataGen 思维",
    "可回滚发布",
]


class DevEnvironmentDocsContractTest(unittest.TestCase):
    def test_required_docs_exist_with_sections(self):
        for rel_path, sections in REQUIRED_FILES.items():
            target = ROOT / rel_path
            self.assertTrue(target.exists(), f"Missing file: {rel_path}")
            text = target.read_text(encoding="utf-8")
            for section in sections:
                self.assertIn(section, text, f"Missing section '{section}' in {rel_path}")

    def test_main_doc_contains_required_snippets(self):
        target = ROOT / "docs/design/dev-environment-baseline-implementation.md"
        self.assertTrue(target.exists(), "Main baseline doc must exist")
        text = target.read_text(encoding="utf-8")
        for snippet in REQUIRED_SNIPPETS:
            self.assertIn(snippet, text, f"Missing command snippet: {snippet}")

    def test_main_doc_contains_minecraft_style_keywords(self):
        target = ROOT / "docs/design/dev-environment-baseline-implementation.md"
        self.assertTrue(target.exists(), "Main baseline doc must exist")
        text = target.read_text(encoding="utf-8")
        for kw in MC_STYLE_KEYWORDS:
            self.assertIn(kw, text, f"Missing Minecraft-style keyword: {kw}")


if __name__ == "__main__":
    unittest.main()
