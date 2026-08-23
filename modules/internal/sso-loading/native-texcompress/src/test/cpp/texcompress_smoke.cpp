// texcompress_smoke - texcompress_core 的一次性离线自测（不进 Gradle 主构建）。
//
// 验证项：
//   1. 256x256 渐变图按 BC1/BC3/BC7 + 三个 quality 档各压缩一次（仅 base 级），
//      校验 SSOBC 头（magic/version/format/mipCount/w/h）与块数据尺寸
//      （BC1 = (w+3)/4*(h+3)/4*8，BC3/BC7 = *16），数据非全零；
//   2. 非 4 倍数尺寸（如 250x130）pad 后尺寸正确；
//   3. mipLevels=99 时层数按尺寸收敛到 1x1（256x256 → 9 级），各级尺寸逐级减半；
//   4. 非法参数（format=9 / w=0 / quality=9）返回 false。
//
// 手动编译运行（仓库根目录）：
//   g++ -std=c++20 -O2 -I modules/internal/sso-loading/native-texcompress/src/main/cpp \
//       modules/internal/sso-loading/native-texcompress/src/test/cpp/texcompress_smoke.cpp \
//       modules/internal/sso-loading/native-texcompress/src/main/cpp/texcompress_core.cpp \
//       modules/internal/sso-loading/native-texcompress/src/main/cpp/vendor/rgbcx.cpp \
//       modules/internal/sso-loading/native-texcompress/src/main/cpp/bc7enc_unit.cpp \
//       -o /tmp/texcompress_smoke && /tmp/texcompress_smoke
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include "texcompress_core.h"

namespace {

int g_failures = 0;

void check(bool cond, const char* what) {
    if (!cond) {
        g_failures++;
        printf("FAIL  %s\n", what);
    } else {
        printf("ok    %s\n", what);
    }
}

uint32_t readU32LE(const uint8_t* p) {
    return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

uint16_t readU16LE(const uint8_t* p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}

// 256x256 RGBA 渐变图（R/G 随坐标变化，B 棋盘格，A 渐变，含 alpha 变化以走 BC7 alpha 路径）。
std::vector<uint8_t> makeGradient(int w, int h) {
    std::vector<uint8_t> px(static_cast<size_t>(w) * h * 4);
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            uint8_t* p = px.data() + (static_cast<size_t>(y) * w + x) * 4;
            p[0] = static_cast<uint8_t>(x * 255 / (w - 1));
            p[1] = static_cast<uint8_t>(y * 255 / (h - 1));
            p[2] = static_cast<uint8_t>(((x / 16 + y / 16) & 1) ? 200 : 40);
            p[3] = static_cast<uint8_t>((x + y) * 255 / (w + h - 2));
        }
    }
    return px;
}

size_t expectedLevelBytes(int w, int h, int format) {
    const size_t blockSize = format == ssotex::FORMAT_BC1 ? 8 : 16;
    return static_cast<size_t>((w + 3) / 4) * ((h + 3) / 4) * blockSize;
}

bool allZero(const uint8_t* p, size_t n) {
    for (size_t i = 0; i < n; i++) {
        if (p[i] != 0) return false;
    }
    return true;
}

void testSingleLevelFull(const std::vector<uint8_t>& px, int w, int h, int format, int quality) {
    std::vector<uint8_t> out;
    char what[128];
    snprintf(what, sizeof(what), "BC%d q%d %dx%d", format, quality, w, h);
    if (!ssotex::compressContainer(format, px.data(), w, h, 1, quality, false, out)) {
        g_failures++;
        printf("FAIL  %s 压缩返回 false\n", what);
        return;
    }
    const size_t dataLen = expectedLevelBytes(w, h, format);
    const bool headerOk = memcmp(out.data(), "SSOB", 4) == 0
            && out[4] == 1 && out[5] == format
            && readU16LE(out.data() + 6) == 1
            && readU32LE(out.data() + 8) == static_cast<uint32_t>(w)
            && readU32LE(out.data() + 12) == static_cast<uint32_t>(h)
            && readU32LE(out.data() + 16) == static_cast<uint32_t>(w)
            && readU32LE(out.data() + 20) == static_cast<uint32_t>(h)
            && readU32LE(out.data() + 24) == static_cast<uint32_t>(dataLen)
            && readU32LE(out.data() + 28) == 0
            && out.size() == 32 + dataLen;
    check(headerOk, what);
    snprintf(what, sizeof(what), "BC%d q%d 数据非全零", format, quality);
    check(!allZero(out.data() + 32, dataLen), what);
}

} // namespace

int main() {
    ssotex::initEncoders();

    const int W = 256, H = 256;
    std::vector<uint8_t> px = makeGradient(W, H);

    // 1. 三格式 x 三质量档，单级
    for (int format : {ssotex::FORMAT_BC1, ssotex::FORMAT_BC3, ssotex::FORMAT_BC7}) {
        for (int quality : {ssotex::QUALITY_FAST, ssotex::QUALITY_NORMAL, ssotex::QUALITY_HIGH}) {
            testSingleLevelFull(px, W, H, format, quality);
        }
    }

    // 2. 非 4 倍数尺寸（250x130）：pad 后块数按 (w+3)/4 计算
    {
        const int w = 250, h = 130;
        std::vector<uint8_t> px2 = makeGradient(w, h);
        std::vector<uint8_t> out;
        check(ssotex::compressContainer(ssotex::FORMAT_BC7, px2.data(), w, h, 1,
                                        ssotex::QUALITY_FAST, false, out),
              "BC7 250x130 压缩成功");
        check(out.size() == 32 + expectedLevelBytes(w, h, ssotex::FORMAT_BC7),
              "BC7 250x130 dataLen 按 pad 后块数");
    }

    // 3. mipLevels=99 → 收敛到 1x1（256x256 共 9 级），各级尺寸逐级减半、dataLen 正确
    {
        std::vector<uint8_t> out;
        check(ssotex::compressContainer(ssotex::FORMAT_BC3, px.data(), W, H, 99,
                                        ssotex::QUALITY_FAST, false, out),
              "BC3 mip 链压缩成功");
        const uint16_t mipCount = readU16LE(out.data() + 6);
        check(mipCount == 9, "BC3 mip 链收敛为 9 级（256→1）");
        size_t expectTotal = 16 + static_cast<size_t>(mipCount) * 16;
        bool dimsOk = true, lensOk = true;
        int w = W, h = H;
        for (uint16_t i = 0; i < mipCount; i++) {
            const uint8_t* e = out.data() + 16 + static_cast<size_t>(i) * 16;
            if (readU32LE(e) != static_cast<uint32_t>(w) || readU32LE(e + 4) != static_cast<uint32_t>(h)) {
                dimsOk = false;
            }
            const size_t len = expectedLevelBytes(w, h, ssotex::FORMAT_BC3);
            if (readU32LE(e + 8) != static_cast<uint32_t>(len) || readU32LE(e + 12) != 0) {
                lensOk = false;
            }
            expectTotal += len;
            w = w > 1 ? w / 2 : 1;
            h = h > 1 ? h / 2 : 1;
        }
        check(dimsOk, "BC3 mip 链各级宽高逐级减半");
        check(lensOk, "BC3 mip 链各级 dataLen 正确");
        check(out.size() == expectTotal, "BC3 mip 链容器总尺寸正确");
    }

    // 4. 非法参数
    {
        std::vector<uint8_t> out;
        check(!ssotex::compressContainer(9, px.data(), W, H, 1, ssotex::QUALITY_FAST, false, out),
              "format=9 拒绝");
        check(!ssotex::compressContainer(ssotex::FORMAT_BC7, px.data(), 0, H, 1,
                                         ssotex::QUALITY_FAST, false, out),
              "width=0 拒绝");
        check(!ssotex::compressContainer(ssotex::FORMAT_BC7, px.data(), W, H, 0,
                                         ssotex::QUALITY_FAST, false, out),
              "mipLevels=0 拒绝");
        check(!ssotex::compressContainer(ssotex::FORMAT_BC7, px.data(), W, H, 1, 9, false, out),
              "quality=9 拒绝");
        check(!ssotex::compressContainer(ssotex::FORMAT_BC7, nullptr, W, H, 1,
                                         ssotex::QUALITY_FAST, false, out),
              "rgba=nullptr 拒绝");
    }

    // 5. BC1 1-bit punch-through alpha（useAlpha=true）：
    //    8x8 图，右侧 4x4 全透明（RGB 仍保留渐变，防止纯色退化掩盖编码缺陷）。
    //    块布局为行优先 (byi * bx + bxi)，右侧两块应为 3-color 模式（c0 <= c1）
    //    且全透明块 selector 全 3（0xFFFFFFFF）；useAlpha=false 时同输入不得出现全 3 selector。
    {
        const int w = 8, h = 8;
        std::vector<uint8_t> pxA(static_cast<size_t>(w) * h * 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                uint8_t* p = pxA.data() + (static_cast<size_t>(y) * w + x) * 4;
                p[0] = static_cast<uint8_t>(x * 30 + 20);
                p[1] = static_cast<uint8_t>(y * 30 + 40);
                p[2] = static_cast<uint8_t>((x + y) * 15 + 60);
                p[3] = x < 4 ? 255 : 0;
            }
        }

        std::vector<uint8_t> outAlpha;
        check(ssotex::compressContainer(ssotex::FORMAT_BC1, pxA.data(), w, h, 1,
                                        ssotex::QUALITY_NORMAL, true, outAlpha),
              "BC1 useAlpha 压缩成功");
        // 容器头 16B + 1 级级别表 16B，数据从偏移 32 开始；8x8 → 2x2 共 4 块，每块 8B
        check(outAlpha.size() == 32 + 4 * 8, "BC1 useAlpha 容器尺寸");
        if (outAlpha.size() == 32 + 4 * 8) {
            const uint8_t* blocks = outAlpha.data() + 32;
            for (int byi = 0; byi < 2; byi++) {
                for (int bxi = 0; bxi < 2; bxi++) {
                    const uint8_t* blk = blocks + (byi * 2 + bxi) * 8;
                    const uint16_t c0 = readU16LE(blk);
                    const uint16_t c1 = readU16LE(blk + 2);
                    const uint32_t sel = readU32LE(blk + 4);
                    char what[128];
                    if (bxi == 1) {
                        // 右侧块：全透明 → c0 == c1 == 0 且 selector 全 3
                        snprintf(what, sizeof(what), "BC1 punch 块(%d,%d) 全透明编码", bxi, byi);
                        check(c0 <= c1 && sel == 0xFFFFFFFFu, what);
                    } else {
                        // 左侧块：全不透明 → rgbcx 主路径（4-color，不得出现 punch 全 3 形态）
                        snprintf(what, sizeof(what), "BC1 punch 块(%d,%d) 不透明主路径", bxi, byi);
                        check(sel != 0xFFFFFFFFu || c0 > c1, what);
                    }
                }
            }
        }

        std::vector<uint8_t> outNoAlpha;
        check(ssotex::compressContainer(ssotex::FORMAT_BC1, pxA.data(), w, h, 1,
                                        ssotex::QUALITY_NORMAL, false, outNoAlpha),
              "BC1 useAlpha=false 压缩成功");
        if (outNoAlpha.size() == 32 + 4 * 8) {
            // alpha 被忽略：右侧块按真实颜色编码，不允许出现全 3 selector 的 3-color 形态
            const uint8_t* blk = outNoAlpha.data() + 32 + 8; // 块 (1,0)
            const uint16_t c0 = readU16LE(blk);
            const uint32_t sel = readU32LE(blk + 4);
            check(!(c0 == 0 && sel == 0xFFFFFFFFu), "BC1 useAlpha=false 忽略 alpha");
        }
    }

    printf(g_failures == 0 ? "\nSMOKE PASS\n" : "\nSMOKE FAIL (%d)\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
