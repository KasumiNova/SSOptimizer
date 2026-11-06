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

}

int main() {
    ssotex::initEncoders();

    const int W = 256, H = 256;
    std::vector<uint8_t> px = makeGradient(W, H);

    for (int format : {ssotex::FORMAT_BC1, ssotex::FORMAT_BC3, ssotex::FORMAT_BC7}) {
        for (int quality : {ssotex::QUALITY_FAST, ssotex::QUALITY_NORMAL, ssotex::QUALITY_HIGH}) {
            testSingleLevelFull(px, W, H, format, quality);
        }
    }

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
                        snprintf(what, sizeof(what), "BC1 punch 块(%d,%d) 全透明编码", bxi, byi);
                        check(c0 <= c1 && sel == 0xFFFFFFFFu, what);
                    } else {
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
            const uint8_t* blk = outNoAlpha.data() + 32 + 8;
            const uint16_t c0 = readU16LE(blk);
            const uint32_t sel = readU32LE(blk + 4);
            check(!(c0 == 0 && sel == 0xFFFFFFFFu), "BC1 useAlpha=false 忽略 alpha");
        }
    }

    printf(g_failures == 0 ? "\nSMOKE PASS\n" : "\nSMOKE FAIL (%d)\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
