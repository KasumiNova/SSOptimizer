/**
 * SSOptimizer 原生库入口。
 * 提供库版本查询等基础 API。
 */
#include "ssoptimizer/optimizer.h"

namespace ssoptimizer {

int32_t optimizer_version() {
    return 1;
}

} // namespace ssoptimizer
