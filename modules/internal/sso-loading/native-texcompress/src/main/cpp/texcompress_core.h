// SSOBC 容器布局（全小端）：
//   头 16B：magic "SSOB"(4) | version u8=1 | format u8 | mipCount u16 | width u32 | height u32
//   级别表（每级 16B）：w u32 | h u32 | dataLen u32 | reserved u32
//   随后按级别顺序紧密拼接各级压缩块数据。
#ifndef SSOPTIMIZER_TEXCOMPRESS_CORE_H
#define SSOPTIMIZER_TEXCOMPRESS_CORE_H

#include <cstdint>
#include <vector>

namespace ssotex {

constexpr int FORMAT_BC1 = 1;
constexpr int FORMAT_BC3 = 3;
constexpr int FORMAT_BC7 = 7;
constexpr int QUALITY_FAST = 0;
constexpr int QUALITY_NORMAL = 1;
constexpr int QUALITY_HIGH = 2;

// 初始化编码器全局表；并发调用安全，重复调用无副作用。
void initEncoders();

/**
 * 将紧密排列的 RGBA8 像素压缩为 SSOBC 容器，成功时覆写 out。
 *
 * rgba 至少包含 width*height*4 字节，width、height 和 mipLevels 必须大于 0。
 * mip 层数在 1x1 或 mipLevels 处截止；useAlpha 仅对 BC1 启用 1-bit punch-through，
 * BC3/BC7 始终编码完整 alpha。参数非法或内存分配失败时返回 false 并输出 stderr 日志。
 */
bool compressContainer(int format, const uint8_t* rgba, int width, int height,
                       int mipLevels, int quality, bool useAlpha, std::vector<uint8_t>& out);

}

#endif
