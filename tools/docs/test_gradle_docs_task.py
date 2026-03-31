#!/usr/bin/env python3
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
BUILD_FILE = ROOT / "build.gradle.kts"


class GradleDocsTaskContractTest(unittest.TestCase):
    def test_docs_check_task_registered(self):
        text = BUILD_FILE.read_text(encoding="utf-8")
        self.assertIn('tasks.register("docsCheck")', text, "docsCheck task must be registered")


if __name__ == "__main__":
    unittest.main()
