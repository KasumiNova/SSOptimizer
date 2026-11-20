# Starsector 0.98a-RC8 与 Java 25 兼容记录

Starsector 0.98a 的官方发行版使用 Java 17。SSOptimizer 独立启动使用 Java 25，并从本机 RC8 安装读取游戏类、资源和原生库。Windows 英文、Windows 常规汉化和原生 Linux 的游戏类存在实际差异。Linux 上的 Windows 来源游戏 JAR 仍使用 Windows 游戏符号，原生库则由宿主平台决定。[官方发行说明](https://fractalsoftworks.com/2025/03/27/starsector-0-98a-release/)

## Java 25 入口实测

使用 OpenJDK 25.0.5 以 `--dry-run -Xverify:all` 解析 `com.fs.starfarer.StarfarerLauncher`。该实验只加载入口，不打开窗口或读取存档。

| 游戏字节码 | 结果 |
| --- | --- |
| Windows 英文 obf | `ClassFormatError`，入口含非法方法名 `new.super`。 |
| 原生 Linux 英文 obf | `ClassFormatError`，入口含非法方法名 `do.new`。默认 JVM 校验也会拒绝。 |
| Windows 来源 named 游戏 | 入口通过解析。 |

常规汉化样本还有空的局部变量调试名。加载路径对实际请求的类规范化非法名称，包括声明和相同符号的引用，并保留资源及合法名称。保持 Java 25 校验开启。替换 XStream 或 Janino 无法修复入口类中的 JVM 格式错误。[独立启动设计](standalone-runtime.md) · [obf 正式包与 dev 开发包](obf-release-reobf.md)

## 游戏侧依赖

| 部件 | 兼容要求 |
| --- | --- |
| XStream | 保留用户明确指定或游戏启动配置使用的 JAR。`SSO_XSTREAM` 支持明确路径。多个候选且缺少明确选择时，提示提供路径。 |
| Janino | 使用游戏对应的编译器及配套库。脚本解析使用实际运行的游戏命名空间。 |
| LWJGL、JInput、OpenAL | 使用游戏安装中与宿主平台匹配的库。RFB 按原 JAR 的 manifest 和来源定义 LWJGL 的密封包。 |
| 日志 | 系统域使用发行中的 Log4j 2 和 1.2 桥。保留游戏日志位置和玩家配置。 |
| JAXB、TXW2、音频、WebP | 保留游戏使用的依赖及服务发现，覆盖 XML 输出、图像与 Ogg 播放场景。 |

XStream 的 lambda 识别、类型许可和 XML 深度是三个独立的兼容问题。游戏战役与模组自建的序列化器保持各自的配置范围。回归覆盖实际存档工厂、脚本编译、输入法、OpenGL、音频和读档往返。游戏与模组各自的格式兼容由读档结果确认，启动器不转换现有存档。
