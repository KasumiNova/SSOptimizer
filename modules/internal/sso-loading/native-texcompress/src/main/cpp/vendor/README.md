# vendor 源码来源

本目录内嵌第三方纹理压缩器源码（verbatim，未改动），用于 libssoptimizer_texcompress。

| 文件 | 来源仓库 | 分支 | 拉取日期 |
| --- | --- | --- | --- |
| `bc7enc.c` / `bc7enc.h` | https://github.com/richgel999/bc7enc | master | 2026-08-22 |
| `rgbcx.cpp` / `rgbcx.h` / `rgbcx_table4.h` | https://github.com/richgel999/bc7enc_rdo | master | 2026-08-22 |

- `bc7enc`：BC7 块压缩器（modes 1/5/6/7），MIT / public domain（见 `bc7enc.c` 尾部许可）。
- `rgbcx`：BC1/BC3 块压缩器，public domain / MIT（见 `rgbcx.h` 尾部许可）。
- `rgbcx_table4.h`：rgbcx 的 cluster fit 加速表，被 `rgbcx.cpp` 引用。

原始拉取命令记录在 `.dev/texcompress-lab/REPORT.md`；本目录与 `.dev/texcompress-lab/vendor/`
内容一致（实验室实测所用的同一份源码）。

## 编译说明

`bc7enc.c` 是 C 源码，但已验证可按 C++20 干净编译（g++ 与 MinGW 交叉均通过）。
Gradle `cpp-library` 插件与 Windows 交叉编译任务只收集 `*.cpp`，因此由
`../bc7enc_unit.cpp` 以 `#include "vendor/bc7enc.c"` 的方式并入 C++ 编译单元，
头文件 `bc7enc.h` 自带 `extern "C"` 守卫，链接无歧义。
