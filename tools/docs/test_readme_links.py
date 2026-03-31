#!/usr/bin/env python3
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
README = ROOT / "README.md"

REQUIRED_LINKS = [
    "docs/design/dev-environment-baseline-implementation.md",
    "docs/design/dev-environment-onboarding-checklist.md",
    "docs/design/dev-environment-mapping-workflow.md",
    "docs/design/dev-environment-run-profiles.md",
    "docs/design/dev-environment-troubleshooting.md",
]


class ReadmeLinksTest(unittest.TestCase):
    def test_readme_contains_dev_environment_links(self):
        text = README.read_text(encoding="utf-8")
        for link in REQUIRED_LINKS:
            self.assertIn(link, text, f"README missing link: {link}")


if __name__ == "__main__":
    unittest.main()
