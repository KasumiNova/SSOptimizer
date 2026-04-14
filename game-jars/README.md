# game-jars — Starsector 平台专属 JAR

本目录存放 Starsector 0.98a-RC8 的**平台专属**混淆 jar，供 CI 构建时替代本地游戏安装目录。

## 目录结构

```
game-jars/
├── linux/        # Linux 版混淆 jar（原版未翻译）
│   ├── fs.common_obf.jar
│   ├── fs.sound_obf.jar
│   ├── starfarer.api.jar
│   └── starfarer_obf.jar
└── windows/      # Windows 版混淆 jar
    ├── fs.common_obf.jar
    ├── fs.sound_obf.jar
    ├── starfarer.api.jar
    └── starfarer_obf.jar
```

## 来源

- **Linux**: Starsector 0.98a-RC8 Linux 安装包原版
- **Windows**: Starsector 0.98a-RC8 Windows 安装包原版（`starsector-core/` 目录）

这 4 个 jar 在 Linux 和 Windows 之间具有不同的混淆映射，因此需要分平台存放。
游戏 classpath 上的其他第三方 jar（LWJGL、XStream 等）已通过 Gradle 依赖引入，
详见 `mapping/build.gradle.kts` 中的 `gameClasspath` 配置。

## 使用方式

当 `starsector.gameDir` 属性为空时（CI 模式），构建系统自动从此目录读取 jar：

```bash
# CI 模式构建 Linux 版
./gradlew build -Pstarsector.gameDir= -Pstarsector.platform=linux -x :native:assemble

# CI 模式构建 Windows 版
./gradlew build -Pstarsector.gameDir= -Pstarsector.platform=windows -x :native:assemble
```

## 注意

- 这些是**原版未修改**的游戏 jar，不包含本地化翻译
- 更新游戏版本时需同步更新此目录中的 jar
- 不要将这些 jar 用于分发或商业用途
