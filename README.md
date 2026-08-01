# SSOptimizer

Starsector 游戏性能优化模组。以 NanoForge coremod 形式装配（ASM / Mixin 字节码织入），在运行时修改游戏引擎行为，无需修改游戏原始文件。

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
4. coremod 入口 jar 会被解压到 `mods/coremods/SSOptimizer.jar`，由 NanoForge 在启动时自动发现装配
5. 安装包会同时写入 `starsector-core/log4j.properties`，用于恢复默认文件日志输出
6. 需要已安装 NanoForge 加载器，通过 NanoForge 提供的启动入口启动游戏

### Linux

1. 下载最新 [Release](https://github.com/KasumiNova/SSOptimizer/releases)
2. 下载并解压 `SSOptimizer-<version>-linux.zip` 到游戏根目录；解压后应得到 `mods/ssoptimizer/`
3. `.fnt` 覆盖字体会被解压到游戏根目录 `graphics/fonts/`；TTF 字体文件会放在 `mods/ssoptimizer/fonts/`；模组本体位于 `mods/ssoptimizer/`，并包含 Linux/Windows 双端原生库
4. coremod 入口 jar 会被解压到 `mods/coremods/SSOptimizer.jar`，由 NanoForge 在启动时自动发现装配
5. 安装包会同时写入游戏根目录 `log4j.properties`，用于恢复默认文件日志输出
6. 确保系统已安装输入法框架（如 fcitx5 + XIM）以使用中文输入功能
7. 通过 NanoForge 启动脚本 `launch_nanoforge_ss.sh` 启动游戏

## 配置

SSOptimizer 的可选开关均为 JVM 系统属性，在 NanoForge 启动脚本的 JVM 参数中传入。完整属性清单见 [系统属性索引](docs/design/system-properties.md)。

### 可选优化开关

舰船引擎火焰渲染替换默认关闭。若需要测试该路径，可在启动脚本 JVM 参数中手动加入：

```text
-Dssoptimizer.render.shipengine.enable=true
```

未加入该参数时，SSOptimizer 不会替换舰船 `Engine.render()` 火焰渲染路径；Sprite、字体 quad、粒子等其他渲染优化不受此开关影响。

### 存档兼容性说明

- SSOptimizer 现在会对 `BaseTiledTerrain` 和 `HyperspaceAutomaton` 的地形 tile 存档优先写入 **Zstd 新格式**。
- **SSOptimizer 可以继续读取旧版原版存档**，也可以读取自己写出的新格式存档。
- **原版未安装 SSOptimizer 的 Starsector 无法读取带 `SSOZ1:` 前缀的新地形 tile 存档内容**；也就是说，用 SSOptimizer 保存后的新存档，不能保证再回到原版直接读取。
- 如果你需要保持对原版读取的写出兼容性，可在 JVM 参数中添加：`-Dssoptimizer.disable.save.terrain.zstd=true`，强制退回旧版 Deflater 写入格式。

### 外部模组优化：DetailedCombatResults（DCR）

针对第三方模组的性能优化源码已收编进 `:app` 模块，经 SPI（`ExternalModOptimizer`）在 coremod 装配时自动注册。首个目标是 **DetailedCombatResults（详细战斗报告）** 的读档热点：

- **L1（默认开，零格式风险）**：读档时 DCR 会在裁剪战报后「清空 + 逐条重写」整份历史（O(N²) 的重序列化 + 压缩）。SSOptimizer 将其合并为「收集 N 次 + 落盘一次」，把该热点从十余秒降到亚秒级。此优化不改变存档格式，卸载 SSOptimizer 后 DCR 仍可正常读档。
- **L2（默认开，可关）**：将 DCR 的压缩内核从 `Deflater` 级别 9 替换为 **Zstd**（基准下大存档约 6–13× 提速），读取时自动识别 Zstd / 旧 Deflate 两种格式，旧存档读出后下一次保存即迁移为 Zstd。
  - **权衡**：DCR 战报压缩串会随存档落盘；一旦迁移为 Zstd，卸载 SSOptimizer（或开启下方子开关）后，DCR 原生 `Inflater` 将无法读取这些战报，会判定数据损坏并清空历史战报记录（仅影响战报分析数据，不影响存档本体）。
  - 如需保持对「无 SSOptimizer 环境」的兼容，可加入：`-Dssoptimizer.disable.dcrzstd=true`，仅保留 L1 合并、压缩走 DCR 原生 Deflater。
- 关闭对 DCR 的全部优化：`-Dssoptimizer.disable.dcr=true`。

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

# 生成 Windows 覆盖安装包（含 mods/ssoptimizer 与 mods/coremods 入口 jar）
./gradlew packageWindowsOverlayZip -Pstarsector.gameDir=/path/to/Starsector
# 产物：build/distributions/SSOptimizer-<version>-windows.zip

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

# 烟测：启动器模式（默认使用游戏根目录的 launch_nanoforge_ss.sh，
# 可用 SSOPTIMIZER_SMOKE_LAUNCH_SCRIPT 环境变量覆盖启动脚本名）
./tools/smoke_test_game_launch.sh <gameDir> <timeoutSec> launcher

# 烟测：游戏模式
./tools/smoke_test_game_launch.sh <gameDir> <timeoutSec> game
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

