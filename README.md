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
3. TTF 字体文件会放在 `mods/ssoptimizer/fonts/`；模组本体位于 `mods/ssoptimizer/`，并包含 Linux/Windows 双端原生库
4. coremod 入口 jar 会被解压到 `mods/coremods/SSOptimizer.jar`，由 NanoForge 在启动时自动发现装配
5. 安装包会同时写入 `starsector-core/log4j.properties`，用于恢复默认文件日志输出
6. 需要已安装 NanoForge 加载器，通过 NanoForge 提供的启动入口启动游戏

### Linux

1. 下载最新 [Release](https://github.com/KasumiNova/SSOptimizer/releases)
2. 下载并解压 `SSOptimizer-<version>-linux.zip` 到游戏根目录；解压后应得到 `mods/ssoptimizer/`
3. TTF 字体文件会放在 `mods/ssoptimizer/fonts/`；模组本体位于 `mods/ssoptimizer/`，并包含 Linux/Windows 双端原生库
4. coremod 入口 jar 会被解压到 `mods/coremods/SSOptimizer.jar`，由 NanoForge 在启动时自动发现装配
5. 安装包会同时写入游戏根目录 `log4j.properties`，用于恢复默认文件日志输出
6. 确保系统已安装输入法框架（如 fcitx5 + XIM）以使用中文输入功能
7. 通过 NanoForge 启动脚本 `launch_nanoforge_ss.sh` 启动游戏

## 配置

SSOptimizer 的可选开关均为 JVM 系统属性，在 NanoForge 启动脚本的 JVM 参数中传入。完整属性清单见 [系统属性索引](docs/design/system-properties.md)。

### 舰船引擎火焰渲染开关

舰船引擎火焰合批渲染默认开启，并按 OpenGL 能力选择可用路径。若需要关闭该路径，可在启动脚本 JVM 参数中加入：

```text
-Dssoptimizer.render.shipengine.enable=false
```

未加入该参数时，SSOptimizer 会替换舰船 `Engine.render()` 火焰渲染路径；Sprite、字体 quad、粒子等其他渲染优化不受此开关影响。

### 存档兼容性说明

- SSOptimizer 会对 `BaseTiledTerrain` 和 `HyperspaceAutomaton` 的地形 tile 存档优先写入 **Zstd 新格式**。
- **SSOptimizer 可以读取原版旧格式存档**，也可以读取自己写出的新格式存档。
- **原版未安装 SSOptimizer 的 Starsector 无法读取带 `SSOZ1:` 前缀的新地形 tile 存档内容**；也就是说，用 SSOptimizer 保存后的新存档，不能保证再回到原版直接读取。
- 如果你需要保持对原版读取的写出兼容性，可在 JVM 参数中添加：`-Dssoptimizer.disable.save.terrain.zstd=true`，强制退回旧版 Deflater 写入格式。

### 外部模组优化：DetailedCombatResults（DCR）

第三方模组优化位于 `:modules:internal:sso-modopt` 模块。DCR 的读档历史合并由 `ExternalModOptimizer` 注册 ASM 处理器，压缩内核由 Mixin 替换：

- **读档历史合并**：DCR 在裁剪战报后会清空并逐条重写整份历史，产生 O(N²) 的重序列化与压缩。SSOptimizer 将多次历史更新合并为一次落盘，不改变存档格式，卸载 SSOptimizer 后 DCR 仍可正常读档。
- **Zstd 压缩**：将 DCR 的 `Deflater` 级别 9 压缩替换为 Zstd，读取时自动识别 Zstd 与旧 Deflate 两种格式。旧存档读出后，下一次保存会迁移为 Zstd。
  - **兼容性影响**：DCR 战报压缩串会随存档落盘。迁移为 Zstd 后，无 SSOptimizer 环境中的 DCR 原生 `Inflater` 无法读取这些战报，会判定数据损坏并清空历史战报记录，但不影响存档本体。
  - 当前没有通过 JVM 属性单独关闭 Zstd 写出的开关；需要保留无 SSOptimizer 环境兼容性时，应先备份存档。
- `-Dssoptimizer.disable.dcr=true` 仅关闭 `DcrModOptimizer` 注册的读档历史合并处理器，不会禁用压缩替换 Mixin。

## 从源码构建

### 环境要求

- JDK 25+
- Gradle 9.x（使用项目自带的 wrapper）
- C++ 20 工具链及所编原生模块的依赖（FreeType、libpng、X11 等）

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

# 生成同时包含 Linux 与 Windows 覆盖目录的统一安装包
./gradlew packageUserModZip -Pstarsector.gameDir=/path/to/Starsector

# 编译全部原生模块：render/loading/texcompress/font/ime
./gradlew \
  :modules:internal:sso-render:native-render:build \
  :modules:internal:sso-loading:native-loading:build \
  :modules:internal:sso-loading:native-texcompress:build \
  :modules:internal:sso-font:native-font:build \
  :modules:internal:sso-ime:native-ime:build

# Linux 主机交叉编译 Windows 原生库（需要 x86_64-w64-mingw32-g++ + vcpkg x64-mingw-static）
./gradlew \
  :modules:internal:sso-render:native-render:build \
  :modules:internal:sso-loading:native-loading:build \
  :modules:internal:sso-loading:native-texcompress:build \
  :modules:internal:sso-font:native-font:build \
  :modules:internal:sso-ime:native-ime:build \
  -Pstarsector.platform=windows \
  -Pssoptimizer.native.windows.triplet=x64-mingw-static

# 部署到游戏目录（开发用）
./gradlew deployMod

# 烟测：启动器模式（默认使用游戏根目录的 launch_nanoforge_ss.sh，
# 可用 SSOPTIMIZER_SMOKE_LAUNCH_SCRIPT 环境变量覆盖启动脚本名）
./tools/smoke_test_game_launch.sh <gameDir> <timeoutSec> launcher

# 烟测：游戏模式
./tools/smoke_test_game_launch.sh <gameDir> <timeoutSec> game
```

### 项目结构

```
SSOptimizer/
├── modules/
│   ├── api/
│   │   └── sso-api/        跨域服务接口与契约 record，零实现、零第三方依赖
│   └── internal/
│       ├── sso-core/       共享基础设施：ASM/Mixin 装配、日志、服务注册、原生库加载、共享线程池
│       ├── sso-app/        装配模块：coremod 入口、跨域服务注册、shade 与游戏运行配置
│       ├── sso-render/     渲染线程、OpenGL bridge 与渲染优化（native-render：GL 批渲染）
│       ├── sso-ai/         战斗 AI 异步并行与碰撞热路径优化
│       ├── sso-loading/    资源加载优化（native-loading：PNG 解码）
│       │   └── native-texcompress/  BC1/BC3/BC7 纹理压缩，独立原生库
│       ├── sso-font/       字体渲染与动态图集（native-font：FreeType 栅格化）
│       ├── sso-ime/        Linux XIM 与 Windows IMM32 输入法（native-ime：原生桥接）
│       ├── sso-save/       存档读写优化与 saveBench 离线基准
│       ├── sso-modopt/     DetailedCombatResults 等第三方模组专项优化
│       ├── sso-automation/ 自动化、基准与启动器辅助
│       └── sso-debug/      默认关闭的 localhost HTTP RPC、动态脚本与热重载调试能力
├── native-headers/         各 Java 模块 compileJava 生成的共享 JNI 头目录
├── tools/                  烟测脚本、日志过滤、IME 调试工具
└── docs/design/            设计文档
```

功能模块只允许依赖 `:modules:api:sso-api` 与 `:modules:internal:sso-core`，功能模块之间禁止直接依赖。唯一既有例外是 `sso-font` 依赖 `sso-render`，用于复用动态图集纹理上传的 `GlDispatch`。`:modules:internal:sso-app` 是装配模块，依赖全部功能模块，不受此限制。

跨域行为调用必须经 `:modules:api:sso-api` 中定义的接口，由 `:modules:internal:sso-app` 的 `SSOptimizerCorePlugin.registerModuleServices()` 注册到 core 的 `ServiceRegistry`，调用方再通过 `ServiceRegistry.require/getOrNull` 解析。

### Linux 主机交叉编译 Windows 原生库

若需要在 Linux 主机直接产出 Windows 版 `ssoptimizer_<module>.dll`，准备以下环境：

- `mingw-w64`，确保 `x86_64-w64-mingw32-g++` / `x86_64-w64-mingw32-gcc` 在 `PATH` 中
- `vcpkg`，并安装 Windows 交叉依赖：`libpng`、`freetype`
- 设置 `VCPKG_ROOT`，或显式传入 `-Pssoptimizer.native.windows.vcpkgRoot=/path/to/vcpkg`

示例：

```bash
# vcpkg 侧准备 Windows 交叉依赖
vcpkg install libpng freetype --triplet x64-mingw-static

# Linux 主机构建 Windows 原生库
./gradlew \
  :modules:internal:sso-render:native-render:build \
  :modules:internal:sso-loading:native-loading:build \
  :modules:internal:sso-loading:native-texcompress:build \
  :modules:internal:sso-font:native-font:build \
  :modules:internal:sso-ime:native-ime:build \
  -Pstarsector.platform=windows \
  -Pssoptimizer.native.windows.triplet=x64-mingw-static
```

若 `libpng` / `freetype` 没有放在 vcpkg 默认目录，也可分别传入：

- `-Pssoptimizer.native.windows.libpng.root=/path/to/libpng/prefix`
- `-Pssoptimizer.native.windows.freetype.root=/path/to/freetype/prefix`

## 设计文档

### 开发环境与配置

- [开发环境基线](docs/design/dev-environment-baseline-implementation.md)
- [开发环境上手清单](docs/design/dev-environment-onboarding-checklist.md)
- [映射与重映射工作流](docs/design/dev-environment-mapping-workflow.md)
- [运行配置与启动档位](docs/design/dev-environment-run-profiles.md)
- [故障排查指引](docs/design/dev-environment-troubleshooting.md)
- [系统属性索引](docs/design/system-properties.md)
- [调试能力设计](docs/design/debug-capability-design.md)

### 渲染与字体

- [渲染与逻辑线程分离切入点](docs/design/render-logic-separation-entrypoints.md)
- [渲染与逻辑线程分离可行性](docs/design/render-logic-separation-feasibility.md)
- [渲染并行录制](docs/design/render-parallel-recording.md)
- [渲染状态命令去重](docs/design/render-state-dedup.md)
- [Native 渲染命令化](docs/design/native-render-command.md)
- [GL bridge 全量镜像](docs/design/gl-bridge-full-mirror.md)
- [GL 调用盘点](docs/design/gl-call-inventory.md)
- [持久映射有序写入桥](docs/design/mapped-write-bridge.md)
- [舰船与武器 Sprite 合批渲染](docs/design/ship-weapon-batch-render.md)
- [战斗渲染与逻辑优化](docs/design/combat-render-logic-optimization.md)
- [运行时文字链路与切入点](docs/design/runtime-text-phase2-entrypoints.md)
- [字体渲染体系重写](docs/design/font-rendering-rewrite.md)
- [GPU 纹理压缩](docs/design/gpu-texture-compression.md)

### 专项优化

- [超空间大地图渲染优化](docs/design/hyperspace-map-optimization.md)
- [星域密集舰队性能优化](docs/design/campaign-fleet-density-optimization.md)
- [DetailedCombatResults 读档热点优化](docs/design/dcr-load-optimization.md)

## 许可证

MIT License

