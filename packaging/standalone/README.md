# SSOptimizer 独立版安装与发布

独立版从已有的 Starsector 0.98a-RC8 安装启动。公开 Windows 安装包使用 `obf`，本地开发与调试使用 `dev`。两个入口都使用游戏安装目录中的模组、启用列表、存档、游戏设置、日志和截图。SSOptimizer 的启动参数、运行锁和缓存位于其独立工作目录。类加载规则见[独立启动设计](../../docs/design/standalone-runtime.md)。

## Windows

1. 从发行页获取并解压 `SSOptimizer-Standalone-<版本>-windows-x64-obf.zip`。打开 `Install.cmd`，选择已有的 Windows x64 Starsector 0.98a-RC8 游戏根目录。安装目录须允许当前用户写入。游戏获取和激活使用 [Starsector 官网](https://fractalsoftworks.com/preorder/)。
2. 从 `<游戏>/mods/ssoptimizer/Launch.cmd` 启动。同目录的 `Manage.cmd` 提供启动、准备和编辑启动参数。安装入口在复制程序文件时核验发行清单签名与其中记录的文件。
3. 安装或重新启用某个版本时，运行对应发行包的 `Install.cmd`。安装器将 `mods/ssoptimizer` 指向 `<游戏>/ssoptimizer-bootstrap/<类型>-<版本>/mod`。该类型与版本已安装且与对应发行包一致时，安装器复用现有版本目录。若模组路径是实际目录，安装器会提示移出该目录并保留原文件。游戏勾选继续由游戏管理。

同一发行类型的各个版本共享 `<游戏>/ssoptimizer-bootstrap/work-<类型>`。每个版本目录的 `work` 联接指向该目录，启动参数和缓存随发行类型保留。版本目录保存宿主、优化负载和打包的 Java 25。SSOptimizer 的模组负载位于发行目录的 `mod/jars/payload.jar`。旧版独立实例中的数据保留原处，由玩家自行决定如何处理。

## Linux 本机

以下示例在 Linux 上运行，使用 Linux 原生库和 Windows RC8 游戏字节码。`--game` 指向已具备这一组合的本机测试安装。`ssoptimizer.native.target` 选择原生库平台。此命令使用默认的 Windows RC8 游戏输入和映射，并通过 `standalone.gameAbi` 标记产物的游戏 ABI。使用 `JAVA_HOME` 指定本机 x64 JDK 25，未设置时使用 `PATH` 中的 Java。

```bash
./gradlew stageStandaloneLinuxObf \
  -Pssoptimizer.native.target=linux -Pstandalone.gameAbi=windows-rc8
build/standalone/linux/obf/version/launch.sh launch --game '/使用 Windows RC8 游戏字节码的 Linux 测试安装'
```

原生 Linux RC8 使用不同的混淆符号。为原生 Linux ABI 构建时，需要通过 `-Pstandalone.obfGameDir` 指定该 ABI 的四个原始核心 JAR 所在目录，通过 `-Pstandalone.namedGameDir` 指定对应的四个 named JAR 所在目录，并通过 `-Pstandalone.mapping` 指定连接二者的映射。`-Pstandalone.gameAbi` 仅命名产物使用的 ABI，实际输入由这三个参数决定。原生 Linux RC8 样本已有 Java 25 类定义实验记录。上面的启动示例覆盖 Windows 游戏字节码与 Linux 原生库的组合。[RC8 兼容记录](../../docs/design/standalone-rc8-java25.md)

开发者可以使用 `stageStandaloneLinuxDev`，入口位于 `build/standalone/linux/dev/version/launch.sh`。脚本在游戏的 `mods/ssoptimizer` 创建指向发行模组目录的符号链接。已有此版本的链接时直接使用，切换发行类型或版本前须先移开现有模组入口。启动器支持 `launch`、`prepare` 和 `config`。工作目录为发行版本目录下的 `work/`，其中的 `jvm.args` 存放此入口的 JVM 参数，`cache/` 存放所需的准备产物。再次运行同一发行类型的 Linux 暂存任务时，现有 `work/` 会保留。`launch` 可在 `--` 后接收游戏参数。测试需要分开存档和设置时，给每组指定不同的游戏安装目录。

## 构建与签名

在配置了 Windows x64 native 工具链的机器上运行 `packageStandaloneWindowsObf` 或 `packageStandaloneWindowsDev`。交叉编译时使用项目的 LLVM MinGW、vcpkg 和 Windows JDK JNI 头文件配置。Windows 包含打包的 Java 25，Linux 暂存任务使用本机 JDK。

```bash
export SSOPTIMIZER_SIGNING_KEY=/发行密钥/standalone-private.pk8
./gradlew packageStandaloneWindowsObf -Pssoptimizer.native.target=windows -Pstarsector.platform=windows
./gradlew packageStandaloneWindowsDev -Pssoptimizer.native.target=windows -Pstarsector.platform=windows
```

暂存目录为 `build/standalone/<linux|windows>/<obf|dev>/version`，Windows ZIP 写入 `build/distributions/`。默认版本号取项目版本，也可通过 `-Pstandalone.version=<版本>` 指定。正式包的私有映射只供构建使用，开发包内含当前 named ABI 对应的映射。参见 [obf 与 dev 构建说明](../../docs/design/obf-release-reobf.md)。

签名使用发行者持有的 RSA PKCS#8 私钥。通过 `SSOPTIMIZER_SIGNING_KEY` 或 `-Pstandalone.signingKey=<路径>` 提供，私钥应存放在仓库和构建输出之外。Windows 发行清单使用 SHA-256/RSA PKCS#1 v1.5。安装器使用发行公钥，无需连接发布服务。发布工作流使用仓库中配置的同一发行公钥核验对应版本的 obf ZIP 内签名。重新分发随包的 JDK 时须保留许可证，并按供应商声明履行源码提供义务。
