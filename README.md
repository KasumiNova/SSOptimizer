# SSOptimizer

Starsector 游戏性能优化 Java Agent。通过字节码注入（ASM / Mixin）在运行时修改游戏引擎行为，无需修改游戏原始文件。

## 核心特性

- **加载性能 3x+ 提升**：并行化资源加载、PNG 原生解码、纹理合批上传
- **字体渲染优化**：FreeType 原生栅格化 + 高 DPI 适配，告别模糊锯齿
- **中文输入支持**：已集成 Linux XIM 与 Windows IMM32 输入法支持
- **渲染管线优化**：OpenGL 批渲染、着色器缓存
- **战斗系统优化**：碰撞检测等热路径性能改进
- **日志降噪**：过滤高频无用日志，减少 I/O 开销

## 安装

### 前置要求

- Starsector 0.98a
- JBR 25 或 Zulu JDK 25（推荐使用项目提供的 JRE）

### Windows

1. 下载最新 [Release](https://github.com/KasumiNova/SSOptimizer/releases)
2. 下载并解压 `SSOptimizer-<version>-windows.zip` 到游戏根目录；解压后应得到 `mods/ssoptimizer/`
3. `.fnt` 覆盖字体会被解压到 `starsector-core/graphics/fonts/`；TTF 字体文件会放在 `mods/ssoptimizer/fonts/`；模组本体位于 `mods/ssoptimizer/`，并包含 Linux/Windows 双端原生库
4. 使用项目提供的 `starsector-ssoptimizer.bat` 启动游戏；它会调用 `starsector-core/starsector.bat`，并按顺序扫描脚本/游戏目录下的所有 `java.exe`、`JAVA_HOME`、以及 `PATH` 中的 Java 25
5. 安装包会同时写入 `starsector-core/log4j.properties`，用于恢复默认文件日志输出
6. 如需手动注入 JVM 参数，可添加：
   ```
   -javaagent:../mods/ssoptimizer/jars/SSOptimizer.jar
   ```
7. 启动游戏，首次运行会在游戏根目录生成 `launch-config.json` 配置文件

### Linux

1. 下载最新 [Release](https://github.com/KasumiNova/SSOptimizer/releases)
2. 下载并解压 `SSOptimizer-<version>-linux.zip` 到游戏根目录；解压后应得到 `mods/ssoptimizer/`
3. `.fnt` 覆盖字体会被解压到游戏根目录 `graphics/fonts/`；TTF 字体文件会放在 `mods/ssoptimizer/fonts/`；模组本体位于 `mods/ssoptimizer/`，并包含 Linux/Windows 双端原生库
4. 安装包会同时写入游戏根目录 `log4j.properties`，用于恢复默认文件日志输出
5. 使用项目提供的 `starsector.sh` 或 `launch_injected_ss.sh` 启动游戏；脚本会按顺序扫描脚本目录下的所有 `java`、随后 `JAVA_HOME`、再扫描 `/usr/lib/jvm` 等系统目录中的 Java 25
6. 如需手动注入 JVM 参数，可在启动脚本中添加：
   ```
   -javaagent:./mods/ssoptimizer/jars/SSOptimizer.jar
   ```
7. 确保系统已安装输入法框架（如 fcitx5 + XIM）以使用中文输入功能
8. 启动游戏

## 配置

配置文件为游戏根目录下的 `launch-config.json`，首次启动自动生成默认配置。各项参数含义见文件内注释。

### 可选优化开关

舰船引擎火焰渲染替换默认关闭。若需要测试该路径，可在 `launch-config.json` 的 `jvmArgs.common` 中手动加入：

```text
-Dssoptimizer.render.shipengine.enable=true
```

未加入该参数时，SSOptimizer 不会替换舰船 `Engine.render()` 火焰渲染路径；Sprite、字体 quad、粒子等其他渲染优化不受此开关影响。

### 存档兼容性说明

- SSOptimizer 现在会对 `BaseTiledTerrain` 和 `HyperspaceAutomaton` 的地形 tile 存档优先写入 **Zstd 新格式**。
- **SSOptimizer 可以继续读取旧版原版存档**，也可以读取自己写出的新格式存档。
- **原版未安装 SSOptimizer 的 Starsector 无法读取带 `SSOZ1:` 前缀的新地形 tile 存档内容**；也就是说，用 SSOptimizer 保存后的新存档，不能保证再回到原版直接读取。
- 如果你需要保持对原版读取的写出兼容性，可在 JVM 参数中添加：`-Dssoptimizer.disable.save.terrain.zstd=true`，强制退回旧版 Deflater 写入格式。

## 从源码构建

### 环境要求

- JDK 25+
- Gradle 9.x（使用项目自带的 wrapper）
- C++ 20 工具链 + FreeType 开发库（编译原生模块需要）

### 构建命令

```bash
# Java 编译 + 单元测试
./gradlew test

# Windows PowerShell / cmd 建议显式传入游戏目录
gradlew.bat test -Pstarsector.gameDir=C:/Data/Games/Starsector098

# 生成 Linux 覆盖安装包
./gradlew packageLinuxOverlayZip -Pstarsector.gameDir=/path/to/Starsector
# 产物：build/distributions/SSOptimizer-<version>-linux.zip

# 生成 Windows 覆盖安装包（解压后直接使用根目录的 starsector-ssoptimizer.bat 启动）
./gradlew packageWindowsOverlayZip -Pstarsector.gameDir=/path/to/Starsector
# 产物：build/distributions/SSOptimizer-<version>-windows.zip

# Windows 直接启动入口
./starsector-ssoptimizer.bat

# 一次性生成双端发布包
./gradlew packageReleaseZips -Pstarsector.gameDir=/path/to/Starsector

# 如需自用的统一 bundle，仍可保留
./gradlew packageUserModZip -Pstarsector.gameDir=/path/to/Starsector

# 原生模块编译
./gradlew :native:build

# Linux 主机交叉编译 Windows 原生库（需要 x86_64-w64-mingw32-g++ + vcpkg x64-mingw-static）
./gradlew :native:build -Pstarsector.platform=windows -Pssoptimizer.native.windows.triplet=x64-mingw-static

# 部署到游戏目录（开发用）
./gradlew installDevMod

# 烟测：启动器模式
./tools/smoke_test_game_launch.sh <gameDir> <timeoutSec> launcher

# 烟测：游戏模式
./tools/smoke_test_game_launch.sh <gameDir> <timeoutSec> game

# Windows 烟测：启动器模式
powershell -ExecutionPolicy Bypass -File ./tools/smoke_test_game_launch.ps1 -GameDir C:/Data/Games/Starsector098 -Mode launcher
```

### 项目结构

```
SSOptimizer/
├── app/           Java 25 主模块（github.kasuminova.ssoptimizer）
├── native/        C++ 原生模块（字体栅格化、PNG 解码、OpenGL 批渲染、Linux IME）
├── tools/         烟测脚本、日志过滤、IME 调试工具
└── docs/design/   设计文档
```

### Linux 主机交叉编译 Windows 原生库

若需要在 Linux 主机直接产出 Windows 版 `ssoptimizer.dll`，推荐准备以下环境：

- `mingw-w64`，确保 `x86_64-w64-mingw32-g++` / `x86_64-w64-mingw32-gcc` 在 `PATH` 中
- `vcpkg`，并安装 Windows 交叉依赖：`libpng`、`freetype`
- 设置 `VCPKG_ROOT`，或显式传入 `-Pssoptimizer.native.windows.vcpkgRoot=/path/to/vcpkg`

示例：

```bash
# vcpkg 侧准备 Windows 交叉依赖
vcpkg install libpng freetype --triplet x64-mingw-static

# Linux 主机构建 Windows 原生库
./gradlew :native:build \
   -Pstarsector.platform=windows \
   -Pssoptimizer.native.windows.triplet=x64-mingw-static
```

若 `libpng` / `freetype` 没有放在 vcpkg 默认目录，也可分别传入：

- `-Pssoptimizer.native.windows.libpng.root=/path/to/libpng/prefix`
- `-Pssoptimizer.native.windows.freetype.root=/path/to/freetype/prefix`

## 设计文档

- [基础开发环境基线](docs/design/dev-environment-baseline-implementation.md)
- [开发环境上手清单](docs/design/dev-environment-onboarding-checklist.md)
- [映射与重映射工作流](docs/design/dev-environment-mapping-workflow.md)
- [运行配置与启动档位](docs/design/dev-environment-run-profiles.md)
- [故障排查指引](docs/design/dev-environment-troubleshooting.md)

## 许可证

MIT License

