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
- `./gradlew bootstrapDev`
- `./gradlew devCycle`
- `./gradlew releasePrepLocal`
- `./gradlew runClient`

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
