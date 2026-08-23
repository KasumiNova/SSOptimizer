// texcompress_core - BC1/BC3/BC7 纹理压缩与 SSOBC 容器组装（无 JNI 依赖）。
//
// 供 JNI 层（ssoptimizer_texcompress.cpp）与离线 smoke（src/test/cpp）共用。
// 编码器：BC7 → vendor bc7enc（modes 1/6 + alpha 5/7），BC1/BC3 → vendor rgbcx（cBC1Ideal）。
//
// SSOBC 容器布局（全小端）：
//   头 16B：magic "SSOB"(4) | version u8=1 | format u8 | mipCount u16 | width u32 | height u32
//   级别表（每级 16B）：w u32 | h u32 | dataLen u32 | reserved u32
//   随后按级别顺序紧密拼接各级压缩块数据。
#ifndef SSOPTIMIZER_TEXCOMPRESS_CORE_H
#define SSOPTIMIZER_TEXCOMPRESS_CORE_H

#include <cstdint>
#include <vector>

namespace ssotex {

// Java 侧 NativeTextureCompressor 的契约常量。
constexpr int FORMAT_BC1 = 1;
constexpr int FORMAT_BC3 = 3;
constexpr int FORMAT_BC7 = 7;
constexpr int QUALITY_FAST = 0;
constexpr int QUALITY_NORMAL = 1;
constexpr int QUALITY_HIGH = 2;

/**
 * 一次性初始化编码器全局表（rgbcx::init(cBC1Ideal) + bc7enc_compress_block_init()）。
 * 内部 std::call_once，多线程并发调用安全。
 */
void initEncoders();

/**
 * 压缩 RGBA8 像素为 SSOBC 容器字节。
 *
 * @param format    FORMAT_BC1/BC3/BC7
 * @param rgba      紧密 RGBA8 像素，长度 >= width*height*4
 * @param mipLevels 1 = 仅 base 级；>1 时逐级 box 下采样，层数按尺寸收敛
 *                  （到 1x1 或给定层数，取小）
 * @param quality   QUALITY_FAST/NORMAL/HIGH
 * @param useAlpha  仅 FORMAT_BC1 有效：true 时含透明像素（alpha < 128）的块按
 *                  1-bit punch-through alpha 编码（3-color 模式，selector 3 = 透明；
 *                  rgbcx 不支持透明色板，该路径由本实现承担），mip 下采样的 alpha
 *                  通道按二值多数决保持 0/255；false 时 BC1 完全忽略 alpha 通道。
 *                  BC3/BC7 始终使用完整 alpha，忽略此参数
 * @param out       输出容器字节（成功时覆写）
 * @return 参数非法或分配失败返回 false（stderr 有一行日志）
 */
bool compressContainer(int format, const uint8_t* rgba, int width, int height,
                       int mipLevels, int quality, bool useAlpha, std::vector<uint8_t>& out);

} // namespace ssotex

#endif // SSOPTIMIZER_TEXCOMPRESS_CORE_H
