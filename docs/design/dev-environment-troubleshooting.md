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
