# SSOptimizer 渲染链路 Mapping 工作流

## 两级映射表职责

| 表 | 文件 | 消费者 | 维护方式 |
|---|---|---|---|
| 运行期权威表 | `mapping/src/main/resources/mappings/ssoptimizer-{platform}.tiny` | agent 内嵌、`RuntimeRemapTransformer`、`reobfuscateAppJar` | 人工，热点驱动 |
| 构建期全量表 | `mapping/build/generated/mappings/{platform}/ssoptimizer-{platform}-full.tiny`（生成物，**不入库**） | `remapGameClasspathToNamed` → named-game-jars → app 编译 / remapped-workspace / IDE | 生成 + 人工条目优先合并 |

- 运行期表刻意不全量：全量改名会改变 XStream 存档序列化中的类名，带来存档兼容风险，因此运行期只保留热点条目。
- 构建期全量表 = 人工表条目（优先，含注释）+ 保持原名片段（见下）+ 结构指纹占位条目（`C_<hash8>` / `f_<hash8>` / `m_<hash8>`，无注释），让开发视图完整可读且无运行期副作用。
- **保持原名片段** `mapping/src/main/resources/mappings/ssoptimizer-identity.tiny`（入库，双平台共用）：登记 app 编译期直接引用、必须保持原名的游戏类（游戏本身未混淆这些真实类名，或 app 以原始混淆名直接引用）。片段中的类与其全部成员在全量表中保持原名，不生成占位名。该片段只被 `generateFullMappings` 消费，运行期不加载。新增 app 直接引用的游戏类时，要么把语义映射迁入人工表，要么在此登记保持原名。
- 全量表描述符统一 **named 存储**：表内类写 named 名，表外类（JDK / 第三方 / 未混淆的 starfarer.api）保持原样；人工条目保留历史混写，解析侧双向兼容。

## 生成 / 合并 / 报告流程

- `./gradlew :mapping:generateFullMappings`：扫描 `game-jars/{platform}/` 下的混淆 jar（`*_obf.jar`），对 linux / windows 各生成一份全量表与报告。生成是确定性的（同一输入两次运行字节一致），有测试锁定。
- `remapGameClasspathToNamed` 依赖 `generateFullMappings` 并通过 `--mapping=` 消费全量表；tiny 源或 game-jars 变更会触发重跑。
- 占位名规则：保留原包前缀，类名 `C_<结构指纹8>`，成员 `f_<指纹8>` / `m_<指纹8>`（成员指纹含描述符，重载天然区分）；同作用域指纹冲突按内部名排序追加 `_2`/`_3`。构造方法与 `<clinit>` 不生成映射；同类同名字段组无法按名消歧，整组保持混淆名。
- 结构指纹 = 父类 + 接口(排序) + 字段描述符多重集 + 方法(desc+access)多重集（剔除 `<clinit>`），SHA-256 截前 8 hex。结构相同的类跨平台指纹一致，是双平台同步命名的锚点。
- 报告（`mapping/build/reports/`，不入库）：
  - `mapping-drift-{platform}.txt`：人工条目在 jar 当前结构中找不到对应类/成员（name+desc 精确匹配）的清单，正常应为 0 条；
  - `cross-platform-match.txt`：双平台类指纹精确匹配数/匹配率与未匹配清单。不匹配项（平台分支/条件编译）只报告，不强行对齐。

## 版本升级增量流程

1. 替换 `game-jars/{platform}/` 为新版本 jar 并更新 `game-jars/README.md` 版本记录。
2. 跑 `./gradlew :mapping:test`：`MappingVsGameJarConsistencyTest` 会列出全部失效人工条目。
3. 跑 `./gradlew :mapping:generateFullMappings`，查看 `mapping-drift-{platform}.txt`——漂移清单就是需要人工处理的全部工作量，未漂移条目无需动。
4. 逐条取证修复或删除漂移条目（同下方"证据链规范"），重复 2–3 直到测试全绿、漂移为 0。
5. 全量表由 jar 确定性重新导出，无需手工迁移。

## 映射注释约定

Tiny v2 注释行（类行下 `\tc <注释>`、成员行下 `\t\tc <注释>`）是映射维护者层面的注释载体，只存在于 mapping 表中，不进入 named-game-jars 字节码。

- 每条人工语义映射的注释记录：**来源（API-impl 种子 | javap 取证 | 运行时验证）+ 置信度 + 证据文件路径**。
- 占位条目（`C_`/`f_`/`m_`）无注释，属生成物。
- 解析与导出（`MappingTableExporter.exportTiny`、全量合并）往返保留注释；未知行仍报错。

## 命名规范
- 类名：语义优先，避免临时缩写
- 方法名：动词开头，体现动作
- 字段名：表达状态含义
- `C_`/`f_`/`m_` 前缀为生成占位名保留，人工命名不得使用

## 证据链规范
每条映射必须包含：
1. 热点来源
2. 反编译证据（方法签名与关键指令）
3. 调用链上下文

## 版本化与审查
- 每次映射变更单独 PR
- 变更必须写明命名理由
- 必须给出风险与回滚策略

## scope 片段批量命名工作流

已在 0.98a-RC8 全量执行一遍：50 scope / 100 片段合入 `mapping/src/main/resources/mappings/scopes/`，`mergeScopeFragments` 与 `generateFullMappings` 全部校验通过。

- **scope 契约**：每 scope 一对 `{scope}-{platform}.tiny`；obf 列写 jar 真实混淆名；named 为完整包路径，双平台 canonical 一致（linux 为 canonical 基准）；成员宁缺毋滥，未映射成员回落占位名；类注释 `\tc 来源|置信度|证据`；证据链落 `.dev/mapping-evidence/{scope}.md`（gitignored）。
- **编排**：每 scope 一个 git worktree（`../SSOptimizer-swarm/{scope}` + `swarm/{scope}` 分支）；分批派发，每批 ≤11 个 scope；合回后主工作区跑质量门 `:mapping:test :mapping:mergeScopeFragments :mapping:generateFullMappings :app:compileJava :app:test`，然后单批单 commit，随后 worktree remove。
- **冲突裁决先例**（三次，均 javap 取证）：
  - 同 obf 类重复 → 留覆盖全 / 证据足的一方，并合并双方独有成员；
  - 不同类同名 → 证据弱方改更具体名（BaseToggleButton、FleetwideCombatReadinessTooltip、DesignDisplay、FighterPickerItem、AptitudePanelCopy、AptitudeSkillRow）；
  - a/b/c 边界 → 以边界类名 + 码点序（`LC_ALL=C sort`）实测切分。

## 双平台取证纪律

后续版本升级重跑时必须遵守，均为实际踩坑教训：

- 指纹对齐必须做内容复核：存在撞指纹假阳性，仅凭指纹不足以确认跨平台对应。
- 成员混淆名双平台不同：windows 侧成员需独立 javap 取证，不能复用 linux 成员混淆名。
- 同名 ≠ 同类、同包名 ≠ 同包：windows 子包可能整体改名。
- windows 片段类集合 = linux 类集合的跨平台对应集，不按 windows 字典序重切 scope。

## identity 遮蔽教训

- 对未混淆类写类级 identity 片段后，named jar 会在同路径提供成员被占位化的副本，遮蔽测试 / 运行时的 obf 视图。
- 凡 app 运行期契约直接依赖成员名的类，必须登记进 `ssoptimizer-identity.tiny`（参照 Ship / CombatState / GenericTextureParticle 先例）。
- 测试 fixture 已改为直接读 vendor jar，绕开 named 副本。

## 遗留疑点

后续跟进清单：

- coreui-a 质疑：ui-core-b-windows 的 `coreui/A/N$Oo → ScrollViewport` 对应关系疑似错误（真正对应可能是 `ui/g$Oo`），待复核。
- 人工表 `CollisionGridQuery`（o0OO / oOoO）命名存疑，语义更像 CollisionGrid。
- GlowBorderTextPanel canonical 不对称：linux `ui/GlowBorderTextPanel` vs windows `campaign/ui/intel/GlowBorderTextPanel`，待统一。
- `combat/systems/F`（ShipSystem 基类）仍在 identity 表，建议迁入人工表语义命名并同步 app ShipAccessor 引用。
- tutorial-a/b 前缀不一致：OoOO 主类 linux 名 TutorialAdvancedCombatScript，tutorial-b 对 `$5..$9` 用了 `AdvancedCombatTutorialScript$` 前缀，待统一。
- campaign-ui-marketinfo-c-windows 存在裸名（无包前缀）named，风格待统一。
- 32 个类仍为占位名（死代码 / 空类 / 匿名类，有意留白）；各 scope 低置信度命名见 `.dev/mapping-evidence/*.md`，待运行期验证提升。
- FighterAutofireManager / AutofireManagerV2 / attack/D 三同构，精确语义待定。

## 版本升级增量流程（0.98.5a 到来时）

1. 换 `game-jars` → 跑 `generateFullMappings` → 漂移报告列出失效人工 / scope 条目。
2. 按 scope 分批重做受影响条目（同一 worktree 编排）。
3. 质量门。
