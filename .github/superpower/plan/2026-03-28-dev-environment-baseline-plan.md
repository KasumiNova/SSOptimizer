# SSOptimizer 开发环境基线实现计划

**Goal:** 在不启动渲染优化编码前，先交付一套“文档先行、体验优先、可验证可回滚”的开发环境基线文档体系，参考 Minecraft 开发生态的工作流标准。

**Architecture:** Docs-as-Code（文档即资产）+ Contract Tests（文档契约测试）+ Gradle 验证入口（docsCheck）+ Mapping 生命周期治理。

**Tech Stack:** Markdown、Python 3（unittest）、Gradle 9.4.1、JUnit5、Java 25、Linux（当前主环境）。

---

## 0. 前置原则（执行约束）

1. 本计划只实现“开发环境基线文档与其验证机制”，不实现渲染优化逻辑。
2. 每个文档变更都必须可通过自动检查（文档契约 + Gradle 任务）。
3. 设计对齐 Minecraft 开发体验：
   - runClient/runServer 等价运行档（Profile）
   - Mappings 生命周期管理
   - Mixin 调试可观测
   - DataGen 思维（可重复生成/校验）
   - 可回滚发布流程

---

## Task 1: 建立文档契约测试（Red）

### Step 1: 编写“文档必备结构”失败测试
- File: `tools/docs/test_dev_environment_baseline.py`
- Code:
```python
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
```

### Step 2: 运行测试并确认失败
- Command: `python3 tools/docs/test_dev_environment_baseline.py`
- Expected output:
```text
FAIL: Missing file: docs/design/dev-environment-baseline-implementation.md
FAIL: Main baseline doc must exist
...
FAILED (failures>=1)
```

### Step 3: 保留失败日志作为 Red 证据
- Artifact: 本地测试输出（截图或 CI 日志链接）

---

## Task 2: 实现主文档（Green-1）

### Step 1: 创建主文档并写入完整内容
- File: `docs/design/dev-environment-baseline-implementation.md`
- Code:
```markdown
# SSOptimizer 开发环境基线实现文档

## 1. 目标与范围

本基线用于在“性能优化编码开始前”统一开发体验：

- 新成员 0.5~1 天完成从安装到可运行；
- 注入开发具备一致的目录、日志、调试和回滚语义；
- 所有过程可被自动化校验，避免口头约定漂移。

## 2. Minecraft 风格环境标准对齐

本项目采用 Minecraft mod 开发生态的成熟方法论：

- runClient 等价流程：使用标准开发运行档，不手填启动参数。
- Mappings 生命周期：先映射后开发，映射资产版本化。
- Mixin 调试开关：可按阶段快速开关注入并定位问题。
- DataGen 思维：能自动生成/校验的内容不靠人工维护。
- 可回滚发布：任意阶段失败可退回上一个稳定配置。

## 3. 工具链与版本基线

- Java: 25
- Gradle Wrapper: 9.4.1
- 测试：JUnit5 + Python unittest（文档契约测试）
- 主要模块：
  - `app`：Java 主逻辑
  - `native`：C++/JNI

标准验证命令：
- `./gradlew doctor`
- `./gradlew :app:test`
- `./gradlew :app:run`
- `./gradlew docsCheck`

## 4. 运行配置（Run Profiles）

### Dev Profile
- 默认开发档。
- 开启必要日志与 Phase 开关。

### Safe Profile
- 禁用所有注入。
- 用于排查“是否注入导致问题”。

### Trace Profile
- 打开细粒度日志与计数埋点。
- 仅用于排障，不用于日常开发。

## 5. IDE 与调试体验基线

- VS Code 与 IntelliJ 均需提供等价启动配置。
- 必须包含：
  - Java 运行配置模板
  - 断点调试说明
  - 热点方法日志过滤建议
- 调试输出必须带 profile 与 phase 标签。

## 6. 混淆映射（Mapping）工作流

1. 从热点图确认优先级
2. 对目标类执行反编译定位
3. 建立 `obf -> named` 映射
4. 追加证据链（热点、签名、调用链）
5. 提交审查并版本化

## 7. Mixin/ASM 注入开发规范

- 注入点必须绑定完整方法签名。
- 每个注入必须有 feature flag。
- 注入失败必须自动降级为无注入路径。
- 变更必须附最小回归测试与回滚说明。

## 8. 测试与质量门

最低通过条件：
1. 文档契约测试通过
2. `doctor` 通过
3. `:app:test` 通过
4. `:app:run` 冒烟通过

## 9. 故障排查与回滚

- 优先切到 Safe Profile 复现。
- 若问题消失：判定注入相关，逐 Phase 回退。
- 若问题不变：转入基础环境排查（JDK/路径/权限）。

## 10. 团队协作规范

- 文档改动必须与实现改动同步提交。
- Mapping 变更必须附证据和风险说明。
- 合并前必须通过 docsCheck。
```

### Step 2: 运行测试并确认“仍失败”（其余文档未补齐）
- Command: `python3 tools/docs/test_dev_environment_baseline.py`
- Expected output:
```text
FAIL: Missing file: docs/design/dev-environment-onboarding-checklist.md
FAIL: Missing file: docs/design/dev-environment-mapping-workflow.md
...
FAILED (failures>=1)
```

---

## Task 3: 实现补充文档（Green-2）

### Step 1: 新成员上手清单
- File: `docs/design/dev-environment-onboarding-checklist.md`
- Code:
```markdown
# SSOptimizer 新成员上手清单

## Day 0 环境准备
- 安装 JDK 25
- 验证 `java -version`
- 验证 `./gradlew -v`
- 配置 Starsector 本地路径与读权限

## Day 1 运行验证
- 执行 `./gradlew doctor`
- 执行 `./gradlew :app:test`
- 执行 `./gradlew :app:run`
- 记录验证结果与环境信息

## Day 2 Mapping 与注入演练
- 选择一个热点方法完成 obf->named 映射
- 补齐证据链（热点图 + javap + 调用链）
- 完成一次最小注入演练并验证 Safe Profile 回退
```

### Step 2: Mapping 工作流文档
- File: `docs/design/dev-environment-mapping-workflow.md`
- Code:
```markdown
# SSOptimizer 渲染链路 Mapping 工作流

## 命名规范
- 类名：语义优先，避免临时缩写
- 方法名：动词开头，体现动作
- 字段名：表达状态含义

## 证据链规范
每条映射必须包含：
1. 热点来源
2. 反编译证据（方法签名与关键指令）
3. 调用链上下文

## 版本化与审查
- 每次映射变更单独 PR
- 变更必须写明命名理由
- 必须给出风险与回滚策略
```

### Step 3: Run Profiles 文档
- File: `docs/design/dev-environment-run-profiles.md`
- Code:
```markdown
# SSOptimizer 开发运行档（Run Profiles）

## Dev Profile
- 用途：日常开发
- 开关：启用必要注入 + 基础日志
- 验证：功能正确与性能初筛

## Safe Profile
- 用途：稳定性与归因
- 开关：禁用所有注入
- 验证：对比基线行为

## Trace Profile
- 用途：深度排障
- 开关：详细日志/统计埋点
- 验证：定位状态污染与签名匹配错误
```

### Step 4: 故障排查文档
- File: `docs/design/dev-environment-troubleshooting.md`
- Code:
```markdown
# SSOptimizer 开发环境故障排查手册

## 常见错误索引
1. JDK 版本不匹配
2. Gradle 缓存损坏
3. 运行路径/权限错误
4. 注入签名不匹配
5. profile 开关配置冲突

## 快速恢复流程
1. 切换 Safe Profile
2. 运行 `./gradlew doctor`
3. 清理并重试测试
4. 禁用最新注入变更
5. 回退至上个稳定版本
```

### Step 5: 运行测试并确认通过
- Command: `python3 tools/docs/test_dev_environment_baseline.py`
- Expected output:
```text
...
Ran 3 tests in X.XXXs

OK
```

---

## Task 4: 将文档检查接入 Gradle（体验强化）

### Step 1: 先写失败测试（docsCheck 任务不存在）
- File: `tools/docs/test_gradle_docs_task.py`
- Code:
```python
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
```

### Step 2: 运行失败测试
- Command: `python3 tools/docs/test_gradle_docs_task.py`
- Expected output:
```text
FAIL: docsCheck task must be registered
```

### Step 3: 在 Gradle 根脚本实现 docsCheck
- File: `build.gradle.kts`
- Code:
```kotlin
tasks.register("docsCheck") {
    group = "verification"
    description = "Validate development-environment baseline docs contract"
    doLast {
        val process = ProcessBuilder("python3", "tools/docs/test_dev_environment_baseline.py")
            .directory(project.rootDir)
            .inheritIO()
            .start()
        val code = process.waitFor()
        if (code != 0) {
            throw GradleException("Documentation contract test failed")
        }
    }
}
```

### Step 4: 运行测试并确认通过
- Command: `python3 tools/docs/test_gradle_docs_task.py`
- Expected output:
```text
...
OK
```

### Step 5: 运行 docsCheck 并确认通过
- Command: `./gradlew docsCheck`
- Expected output:
```text
> Task :docsCheck
...
BUILD SUCCESSFUL
```

---

## Task 5: README 接入文档导航（发现性优化）

### Step 1: 编写失败测试（README 缺少链接）
- File: `tools/docs/test_readme_links.py`
- Code:
```python
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
```

### Step 2: 运行失败测试
- Command: `python3 tools/docs/test_readme_links.py`
- Expected output:
```text
FAIL: README missing link: docs/design/dev-environment-baseline-implementation.md
```

### Step 3: 在 README 增加文档导航段
- File: `README.md`
- Code:
```markdown
## 开发环境基线文档

- [开发环境基线实现文档](docs/design/dev-environment-baseline-implementation.md)
- [新成员上手清单](docs/design/dev-environment-onboarding-checklist.md)
- [渲染链路 Mapping 工作流](docs/design/dev-environment-mapping-workflow.md)
- [开发运行档（Run Profiles）](docs/design/dev-environment-run-profiles.md)
- [故障排查手册](docs/design/dev-environment-troubleshooting.md)
```

### Step 4: 运行测试并确认通过
- Command: `python3 tools/docs/test_readme_links.py`
- Expected output:
```text
...
OK
```

---

## Task 6: 最终收口验证

### Step 1: 运行文档契约测试
- Command: `python3 tools/docs/test_dev_environment_baseline.py`
- Expected output: `OK`

### Step 2: 运行 Gradle 文档任务
- Command: `./gradlew docsCheck`
- Expected output: `BUILD SUCCESSFUL`

### Step 3: 运行现有工程健康检查
- Command: `./gradlew doctor :app:test`
- Expected output: `BUILD SUCCESSFUL`

### Step 4: 记录交付验收
- Artifact:
  - docsCheck 成功日志
  - 3 份关键文档链接
  - README 导航截图或链接

---

## 验证清单（执行前自检）

- [ ] 全部文档路径与章节名一致
- [ ] 所有命令均可在仓库根目录执行
- [ ] 每个新测试都经历 Red -> Green
- [ ] `docsCheck` 已接入 Gradle 并通过
- [ ] 不包含任何渲染优化业务代码变更

---

## 交接说明

本计划完成后，交给 `superpower-execute` 执行。
执行阶段必须严格按任务顺序推进，保留每个 Red/Green 证据输出。