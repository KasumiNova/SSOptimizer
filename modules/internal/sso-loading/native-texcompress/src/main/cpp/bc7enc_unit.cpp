// Gradle cpp-library 与 Windows 交叉编译只收集 *.cpp，必须通过本编译单元纳入 C 源码；头文件的 extern "C" 守卫保持 C 链接。
#include "vendor/bc7enc.c"
