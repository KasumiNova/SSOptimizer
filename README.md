# SSOptimizer

Starsector 游戏性能优化 Java Agent。通过字节码注入（ASM / Mixin）在运行时修改游戏引擎行为，无需修改游戏原始文件。

## 核心特性

- **加载性能 3x+ 提升**：并行化资源加载、PNG 原生解码、纹理合批上传
- **字体渲染优化**：FreeType 原生栅格化 + 高 DPI 适配，告别模糊锯齿
- **中文输入支持**：Linux XIM 协议集成，支持 fcitx5 等输入法框架（Windows 支持计划中）
- **渲染管线优化**：OpenGL 批渲染、着色器缓存
- **战斗系统优化**：碰撞检测等热路径性能改进
- **日志降噪**：过滤高频无用日志，减少 I/O 开销

## 安装

### 前置要求

- Starsector 0.98a
- JBR 25 或 Zulu JDK 25（推荐使用项目提供的 JRE）

### Windows

1. 下载最新 [Release](https://github.com/KasumiNova/SSOptimizer/releases)
2. 解压到游戏 `mods/` 目录，确保路径为 `mods/ssoptimizer/`
3. 使用项目提供的 `starsector.bat` 启动游戏，或手动在 JVM 参数中添加：
   ```
   -javaagent:./mods/ssoptimizer/jars/SSOptimizer.jar
   ```
4. 启动游戏，首次运行会在游戏根目录生成 `launch-config.json` 配置文件

### Linux

1. 下载最新 [Release](https://github.com/KasumiNova/SSOptimizer/releases)
2. 解压到游戏 `mods/` 目录，确保路径为 `mods/ssoptimizer/`
3. 使用项目提供的 `starsector.sh` 启动游戏，或手动在启动脚本中添加 JVM 参数：
   ```
   -javaagent:./mods/ssoptimizer/jars/SSOptimizer.jar
   ```
4. 确保系统已安装输入法框架（如 fcitx5 + XIM）以使用中文输入功能
5. 启动游戏

## 配置

配置文件为游戏根目录下的 `launch-config.json`，首次启动自动生成默认配置。各项参数含义见文件内注释。

## 从源码构建

### 环境要求

- JDK 25+
- Gradle 9.x（使用项目自带的 wrapper）
- C++ 20 工具链 + FreeType 开发库（编译原生模块需要）

### 构建命令

```bash
# Java 编译 + 单元测试
./gradlew test

# 原生模块编译
./gradlew :native:build

# 部署到游戏目录（开发用）
./gradlew installDevMod

# 烟测：启动器模式
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

## 许可证

MIT License

