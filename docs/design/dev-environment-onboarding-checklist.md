# SSOptimizer 新成员上手清单

## Day 0 环境准备
- 安装 JDK 25
- 验证 `java -version`
- 验证 `./gradlew -v`
- 配置 Starsector 本地路径与读权限

## Day 1 运行验证
- 执行 `./gradlew doctor`
- 执行 `./gradlew :app:test`
- 执行 `./gradlew :app:run`
- 记录验证结果与环境信息

## Day 2 Mapping 与注入演练
- 选择一个热点方法完成 obf->named 映射
- 补齐证据链（热点图 + javap + 调用链）
- 完成一次最小注入演练并验证 Safe Profile 回退
