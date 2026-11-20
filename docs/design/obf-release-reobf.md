# RC8 的 obf 正式包与 dev 开发包

SSOptimizer 以 named 游戏符号开发。玩家使用 obf 正式包，开发者使用 dev 包。两种包都读取已有游戏安装中的核心 JAR、资源、模组和玩家数据。发布命令见[安装与发布说明](../../packaging/standalone/README.md)。

| 构件 | 发行范围 | 游戏符号 |
| --- | --- | --- |
| `SSOptimizer-Standalone-<版本>-windows-x64-obf.zip` | Windows x64 公开发行 | SSOptimizer 构件使用目标 obf ABI。玩家端不携带 Tiny 映射、refmap、游戏 JAR 或转换后的游戏 JAR。 |
| `SSOptimizer-Standalone-<版本>-windows-x64-dev.zip` | 本地开发与调试 | SSOptimizer 构件使用 named ABI，开发包附带匹配的 Tiny 映射。 |

## 构建期转换

构建机读取私有映射，把 SSOptimizer 对游戏类、方法、字段的引用写成目标 obf 符号。覆盖 JVM 描述符、调用和句柄、Mixin 注解选择器及 ASM 指令中直接使用的游戏符号。Mixin 选择器按目标 owner、成员和描述符解析。构建失败时保留具体归档和符号诊断。SSOptimizer 自身的 API 和普通第三方库符号沿用原名。

Windows 和 Linux 原生发行的混淆符号存在差异。目标 ABI 由构建输入决定。宿主平台决定运行时和 native，不能用于推断游戏符号。开发构件使用 named ABI，相关映射不随 obf 构件分发。

## 按需加载与名称修复

Java 25 实测拒绝 Windows 英文 RC8 启动类中的 `new.super`，也拒绝原生 Linux 英文 RC8 的 `do.new`。普通 Java 关键字作为 class 文件成员名可以链接，无需重命名。常规汉化样本还出现了空的 `LocalVariableTable` 名称。修复规则须保留合法符号，并对同一非法符号的声明和引用使用同一转换，同时保留有效调试条目的类型、作用域和槽位。参见 [JVMS 名称规则](https://docs.oracle.com/javase/specs/jvms/se25/html/jvms-4.html#jvms-4.2)和 [RC8 实测记录](standalone-rc8-java25.md)。

此前的 Java 25 类定义实验读取每组 RC8 的四个核心 JAR。在每组 6,382 个被请求的类中，未经修复的 Windows 英文样本失败 1,255 个，Linux 原生英文样本失败 1,227 个，Windows 常规汉化样本失败 1,258 个。修复非法成员名后，汉化样本仍有 3 个类因空局部变量名失败。补上合法调试名后，三组全部通过该实验。这些结果只涉及类定义和链接。

obf 引导通过 RFB 获取游戏类，在 Mixin 读取目标和 JVM 定义前规范化所请求的字节。dev 在准备阶段转换四个固定游戏核心 JAR 和启用模组显式声明的 obf JAR，已标记 named 的输入直接挂载。游戏资源由原安装提供。模组顶层 JAR 的顺序来自游戏元数据，manifest 的 `Class-Path` 依赖相对于该模组原始 JAR 解析。见[独立启动设计](standalone-runtime.md)。

## 存档与发布

读取和保存都使用现有游戏安装的数据路径。切换入口或版本不执行存档迁移。存档兼容以具体游戏发行、写档时的命名空间与实际启用的模组为准。读回回归在用户授权的隔离样本上运行，保留原始槽位。

发行构建签署 Windows 安装包的发行清单。公开发布使用对应版本的 obf ZIP 和仓库配置的发行公钥。静态链接诊断、完整类探针及实际游戏织入回归由开发者按具体需求执行，常规构建和正式玩家启动不以这些诊断为前置条件。
