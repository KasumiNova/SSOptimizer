#include "texcompress_core.h"

#include <cstdio>
#include <cstring>
#include <mutex>

#include "vendor/bc7enc.h"
#include "vendor/rgbcx.h"

namespace ssotex {
namespace {

constexpr uint32_t SSOBC_VERSION = 1;
constexpr size_t SSOBC_HEADER_SIZE = 16;
constexpr size_t SSOBC_LEVEL_ENTRY_SIZE = 16;

std::once_flag g_encoderInitOnce;

uint32_t rgbcxLevelForQuality(int quality) {
    switch (quality) {
        case QUALITY_FAST: return 10;
        case QUALITY_HIGH: return 18;
        default: return 12;
    }
}

bc7enc_compress_block_params bc7ParamsForQuality(int quality) {
    bc7enc_compress_block_params params;
    bc7enc_compress_block_params_init(&params);
    switch (quality) {
        case QUALITY_FAST:
            params.m_max_partitions_mode = 16;
            params.m_try_least_squares = BC7ENC_FALSE;
            break;
        case QUALITY_HIGH:
            params.m_uber_level = 1;
            params.m_mode_partition_estimation_filterbank = BC7ENC_FALSE;
            break;
        default:
            break;
    }
    return params;
}

size_t blockSizeForFormat(int format) {
    return format == FORMAT_BC1 ? 8 : 16;
}

int resolveMipCount(int width, int height, int mipLevels) {
    int count = 1;
    int w = width, h = height;
    while ((w > 1 || h > 1) && count < mipLevels) {
        w = w > 1 ? w / 2 : 1;
        h = h > 1 ? h / 2 : 1;
        count++;
    }
    return count;
}

std::vector<uint8_t> padTo4(const uint8_t* px, int w, int h, int& pw, int& ph) {
    pw = (w + 3) & ~3;
    ph = (h + 3) & ~3;
    if (pw == w && ph == h) {
        return std::vector<uint8_t>(px, px + static_cast<size_t>(w) * h * 4);
    }
    std::vector<uint8_t> out(static_cast<size_t>(pw) * ph * 4);
    for (int y = 0; y < ph; y++) {
        const int sy = y < h ? y : h - 1;
        const uint8_t* src = px + static_cast<size_t>(sy) * w * 4;
        uint8_t* dst = out.data() + static_cast<size_t>(y) * pw * 4;
        memcpy(dst, src, static_cast<size_t>(w) * 4);
        for (int x = w; x < pw; x++) {
            memcpy(dst + static_cast<size_t>(x) * 4, dst + static_cast<size_t>(w - 1) * 4, 4);
        }
    }
    return out;
}

// BC1 punch-through 的 mip alpha 必须保持 0/255，按透明像素多数决避免算术均值产生无法编码的中间值。
std::vector<uint8_t> downsample2x(const uint8_t* src, int w, int h, bool binaryAlpha, int& dw, int& dh) {
    dw = w > 1 ? w / 2 : 1;
    dh = h > 1 ? h / 2 : 1;
    std::vector<uint8_t> out(static_cast<size_t>(dw) * dh * 4);
    for (int y = 0; y < dh; y++) {
        const int sy0 = y * 2;
        const int sy1 = sy0 + 1 < h ? sy0 + 1 : h - 1;
        for (int x = 0; x < dw; x++) {
            const int sx0 = x * 2;
            const int sx1 = sx0 + 1 < w ? sx0 + 1 : w - 1;
            uint8_t* dst = out.data() + (static_cast<size_t>(y) * dw + x) * 4;
            const uint8_t* s00 = src + (static_cast<size_t>(sy0) * w + sx0) * 4;
            const uint8_t* s01 = src + (static_cast<size_t>(sy0) * w + sx1) * 4;
            const uint8_t* s10 = src + (static_cast<size_t>(sy1) * w + sx0) * 4;
            const uint8_t* s11 = src + (static_cast<size_t>(sy1) * w + sx1) * 4;
            for (int c = 0; c < 3; c++) {
                const uint32_t sum = static_cast<uint32_t>(s00[c]) + s01[c] + s10[c] + s11[c];
                dst[c] = static_cast<uint8_t>((sum + 2) / 4);
            }
            if (binaryAlpha) {
                const int transparent = (s00[3] < 128 ? 1 : 0) + (s01[3] < 128 ? 1 : 0)
                        + (s10[3] < 128 ? 1 : 0) + (s11[3] < 128 ? 1 : 0);
                dst[3] = transparent >= 2 ? 0 : 255;
            } else {
                const uint32_t sum = static_cast<uint32_t>(s00[3]) + s01[3] + s10[3] + s11[3];
                dst[3] = static_cast<uint8_t>((sum + 2) / 4);
            }
        }
    }
    return out;
}

uint16_t pack565(const uint8_t* rgb) {
    return static_cast<uint16_t>(((rgb[0] >> 3) << 11) | ((rgb[1] >> 2) << 5) | (rgb[2] >> 3));
}

void expand565(uint16_t c, uint8_t* rgb) {
    const uint32_t r = (c >> 11) & 0x1F;
    const uint32_t g = (c >> 5) & 0x3F;
    const uint32_t b = c & 0x1F;
    rgb[0] = static_cast<uint8_t>((r << 3) | (r >> 2));
    rgb[1] = static_cast<uint8_t>((g << 2) | (g >> 4));
    rgb[2] = static_cast<uint8_t>((b << 3) | (b >> 2));
}

bool blockHasTransparentTexel(const uint8_t* block) {
    for (int i = 0; i < 16; i++) {
        if (block[i * 4 + 3] < 128) {
            return true;
        }
    }
    return false;
}

void encodeBc1PunchThroughBlock(uint8_t* dst, const uint8_t* block) {
    int minLum = INT32_MAX, maxLum = INT32_MIN;
    uint8_t minColor[3] = {0, 0, 0};
    uint8_t maxColor[3] = {0, 0, 0};
    int opaqueCount = 0;
    for (int i = 0; i < 16; i++) {
        const uint8_t* p = block + i * 4;
        if (p[3] < 128) {
            continue;
        }
        opaqueCount++;
        const int lum = p[0] * 2 + p[1] * 4 + p[2];
        if (lum < minLum) {
            minLum = lum;
            memcpy(minColor, p, 3);
        }
        if (lum > maxLum) {
            maxLum = lum;
            memcpy(maxColor, p, 3);
        }
    }

    if (opaqueCount == 0) {
        dst[0] = dst[1] = dst[2] = dst[3] = 0;
        dst[4] = dst[5] = dst[6] = dst[7] = static_cast<uint8_t>(0xFF);
        return;
    }

    uint16_t c0 = pack565(minColor);
    uint16_t c1 = pack565(maxColor);
    // 必须保持 c0 <= c1，否则 GPU 会按四色不透明模式解码。
    if (c0 > c1) {
        const uint16_t tmp = c0;
        c0 = c1;
        c1 = tmp;
    }

    uint8_t palette[3][3];
    expand565(c0, palette[0]);
    expand565(c1, palette[1]);
    for (int c = 0; c < 3; c++) {
        palette[2][c] = static_cast<uint8_t>((static_cast<uint32_t>(palette[0][c]) + palette[1][c]) / 2);
    }

    uint32_t selectors = 0;
    for (int i = 0; i < 16; i++) {
        const uint8_t* p = block + i * 4;
        uint32_t selector = 3;
        if (p[3] >= 128) {
            uint32_t bestDistance = UINT32_MAX;
            selector = 0;
            for (uint32_t candidate = 0; candidate < 3; candidate++) {
                const int dr = p[0] - palette[candidate][0];
                const int dg = p[1] - palette[candidate][1];
                const int db = p[2] - palette[candidate][2];
                const uint32_t distance = static_cast<uint32_t>(dr * dr + dg * dg + db * db);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    selector = candidate;
                }
            }
        }
        selectors |= selector << (i * 2);
    }

    dst[0] = static_cast<uint8_t>(c0 & 0xFF);
    dst[1] = static_cast<uint8_t>(c0 >> 8);
    dst[2] = static_cast<uint8_t>(c1 & 0xFF);
    dst[3] = static_cast<uint8_t>(c1 >> 8);
    dst[4] = static_cast<uint8_t>(selectors & 0xFF);
    dst[5] = static_cast<uint8_t>((selectors >> 8) & 0xFF);
    dst[6] = static_cast<uint8_t>((selectors >> 16) & 0xFF);
    dst[7] = static_cast<uint8_t>((selectors >> 24) & 0xFF);
}

std::vector<uint8_t> encodeLevel(const uint8_t* px, int w, int h, int format, bool useAlpha,
                                 const bc7enc_compress_block_params& bc7Params, uint32_t rgbcxLevel) {
    int pw, ph;
    std::vector<uint8_t> padded = padTo4(px, w, h, pw, ph);
    const int bx = pw / 4, by = ph / 4;
    const size_t blockSize = blockSizeForFormat(format);
    std::vector<uint8_t> out(static_cast<size_t>(bx) * by * blockSize);
    for (int byi = 0; byi < by; byi++) {
        for (int bxi = 0; bxi < bx; bxi++) {
            uint8_t block[64];
            for (int y = 0; y < 4; y++) {
                memcpy(block + y * 16, padded.data() + (static_cast<size_t>(byi * 4 + y) * pw + bxi * 4) * 4, 16);
            }
            uint8_t* dst = out.data() + (static_cast<size_t>(byi) * bx + bxi) * blockSize;
            if (format == FORMAT_BC7) {
                bc7enc_compress_block(dst, block, &bc7Params);
            } else if (format == FORMAT_BC3) {
                rgbcx::encode_bc3(rgbcxLevel, dst, block);
            } else if (useAlpha && blockHasTransparentTexel(block)) {
                encodeBc1PunchThroughBlock(dst, block);
            } else {
                rgbcx::encode_bc1(rgbcxLevel, dst, block, false, false);
            }
        }
    }
    return out;
}

void putU16LE(uint8_t* p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v & 0xFF);
    p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
}

void putU32LE(uint8_t* p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v & 0xFF);
    p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
    p[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
    p[3] = static_cast<uint8_t>((v >> 24) & 0xFF);
}

}

void initEncoders() {
    std::call_once(g_encoderInitOnce, []() {
        rgbcx::init(rgbcx::bc1_approx_mode::cBC1Ideal);
        bc7enc_compress_block_init();
    });
}

bool compressContainer(int format, const uint8_t* rgba, int width, int height,
                       int mipLevels, int quality, bool useAlpha, std::vector<uint8_t>& out) {
    if (format != FORMAT_BC1 && format != FORMAT_BC3 && format != FORMAT_BC7) {
        fprintf(stderr, "[SSOptimizer] texcompress: 非法 format=%d\n", format);
        return false;
    }
    if (rgba == nullptr || width < 1 || height < 1 || mipLevels < 1
            || quality < QUALITY_FAST || quality > QUALITY_HIGH) {
        fprintf(stderr, "[SSOptimizer] texcompress: 非法参数 w=%d h=%d mips=%d quality=%d rgba=%p\n",
                width, height, mipLevels, quality, static_cast<const void*>(rgba));
        return false;
    }

    initEncoders();

    try {
        const bc7enc_compress_block_params bc7Params = bc7ParamsForQuality(quality);
        const uint32_t rgbcxLevel = rgbcxLevelForQuality(quality);
        const int mipCount = resolveMipCount(width, height, mipLevels);
        const bool binaryAlphaDownsample = format == FORMAT_BC1 && useAlpha;

        std::vector<std::vector<uint8_t>> levels;
        std::vector<std::pair<int, int>> levelDims;
        levels.reserve(mipCount);
        levelDims.reserve(mipCount);

        std::vector<uint8_t> current(rgba, rgba + static_cast<size_t>(width) * height * 4);
        int cw = width, ch = height;
        for (int i = 0; i < mipCount; i++) {
            if (i > 0) {
                int nw, nh;
                current = downsample2x(current.data(), cw, ch, binaryAlphaDownsample, nw, nh);
                cw = nw;
                ch = nh;
            }
            levels.push_back(encodeLevel(current.data(), cw, ch, format, useAlpha, bc7Params, rgbcxLevel));
            levelDims.emplace_back(cw, ch);
        }

        size_t total = SSOBC_HEADER_SIZE + SSOBC_LEVEL_ENTRY_SIZE * levels.size();
        for (const auto& level : levels) {
            total += level.size();
        }
        out.assign(total, 0);

        uint8_t* header = out.data();
        memcpy(header, "SSOB", 4);
        header[4] = static_cast<uint8_t>(SSOBC_VERSION);
        header[5] = static_cast<uint8_t>(format);
        putU16LE(header + 6, static_cast<uint32_t>(levels.size()));
        putU32LE(header + 8, static_cast<uint32_t>(width));
        putU32LE(header + 12, static_cast<uint32_t>(height));

        uint8_t* entry = header + SSOBC_HEADER_SIZE;
        uint8_t* data = entry + SSOBC_LEVEL_ENTRY_SIZE * levels.size();
        for (size_t i = 0; i < levels.size(); i++) {
            uint8_t* e = entry + i * SSOBC_LEVEL_ENTRY_SIZE;
            putU32LE(e + 0, static_cast<uint32_t>(levelDims[i].first));
            putU32LE(e + 4, static_cast<uint32_t>(levelDims[i].second));
            putU32LE(e + 8, static_cast<uint32_t>(levels[i].size()));
            putU32LE(e + 12, 0);
            memcpy(data, levels[i].data(), levels[i].size());
            data += levels[i].size();
        }
        return true;
    } catch (const std::bad_alloc&) {
        fprintf(stderr, "[SSOptimizer] texcompress: 内存分配失败 w=%d h=%d format=%d\n", width, height, format);
        return false;
    }
}

}
