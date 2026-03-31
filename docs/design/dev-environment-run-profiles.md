# SSOptimizer 开发运行档（Run Profiles）

## Dev Profile
- 用途：日常开发
- 开关：启用必要注入 + 基础日志
- 验证：功能正确与性能初筛

## Safe Profile
- 用途：稳定性与归因
- 开关：禁用所有注入
- 验证：对比基线行为

## Trace Profile
- 用途：深度排障
- 开关：详细日志/统计埋点
- 验证：定位状态污染与签名匹配错误
