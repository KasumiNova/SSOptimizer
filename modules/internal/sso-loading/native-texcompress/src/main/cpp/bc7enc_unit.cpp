// bc7enc 编译单元：vendor/bc7enc.c 是 C 源码，而 Gradle cpp-library 与 Windows
// 交叉编译任务只收集 *.cpp。该文件已验证可按 C++20 干净编译（头文件自带
// extern "C" 守卫），故以本单元内嵌引入，避免改动共享构建插件。
#include "vendor/bc7enc.c"
