# SSOptimizer 开发环境故障排查手册

## 常见错误索引
1. JDK 版本不匹配
2. Gradle 缓存损坏
3. 运行路径/权限错误
4. 注入签名不匹配
5. profile 开关配置冲突

## 快速恢复流程
1. 切换 Safe Profile
2. 运行 `./gradlew doctor`
3. 清理并重试测试
4. 禁用最新注入变更
5. 回退至上个稳定版本

## 存档兼容性提示
- 地形 tile 压缩优化默认会把 `BaseTiledTerrain` / `HyperspaceAutomaton` 写成带 `SSOZ1:` 前缀的 Zstd 新格式。
- SSOptimizer 保持“旧存档可读 + 新存档可读”，但**原版 Starsector 不保证可读取这些新写出的存档**。
- 若需要排查“装了优化能读、卸掉优化读不回”的问题，先确认是否启用了该新格式写入。
- 临时回退到原版兼容写出时，可在 JVM 参数中添加：`-Dssoptimizer.disable.save.terrain.zstd=true`。
