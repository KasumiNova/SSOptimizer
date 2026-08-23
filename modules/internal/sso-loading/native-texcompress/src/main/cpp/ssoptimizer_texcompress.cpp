/**
 * 原生 BC 纹理压缩器（JNI 实现）。
 *
 * 对应 Java 类: github.kasuminova.ssoptimizer.common.loading.NativeTextureCompressor
 * 编码核心在 texcompress_core（BC7 → vendor bc7enc，BC1/BC3 → vendor rgbcx），
 * 本文件只做 JNI 边界参数校验、direct ByteBuffer 取值与 byte[] 回传。
 * C++ 异常不越 JNI 边界；所有失败路径返回 nullptr 并在 stderr 留一行日志。
 */
#include <cstdint>
#include <cstdio>
#include <vector>

#include <jni.h>

#include "github_kasuminova_ssoptimizer_common_loading_NativeTextureCompressor.h"
#include "texcompress_core.h"
#include "vendor/bc7enc.h"
#include "vendor/rgbcx.h"

namespace {

// 一次性自测：压缩一个 4x4 块走通三种格式的编码路径，防御 vendor 表初始化缺失。
bool selfTest() {
    uint8_t pixels[64];
    for (int i = 0; i < 16; i++) {
        pixels[i * 4 + 0] = static_cast<uint8_t>(i * 16);
        pixels[i * 4 + 1] = static_cast<uint8_t>(255 - i * 16);
        pixels[i * 4 + 2] = static_cast<uint8_t>(i * 8);
        pixels[i * 4 + 3] = 255;
    }
    std::vector<uint8_t> out;
    for (int format : {ssotex::FORMAT_BC1, ssotex::FORMAT_BC3, ssotex::FORMAT_BC7}) {
        if (!ssotex::compressContainer(format, pixels, 4, 4, 1, ssotex::QUALITY_FAST, false, out)) {
            fprintf(stderr, "[SSOptimizer] texcompress: 自测失败 format=%d\n", format);
            return false;
        }
    }
    // BC1 punch-through 路径：半块透明像素，走自实现 3-color + selector 3 编码
    for (int i = 8; i < 16; i++) {
        pixels[i * 4 + 3] = 0;
    }
    if (!ssotex::compressContainer(ssotex::FORMAT_BC1, pixels, 4, 4, 1, ssotex::QUALITY_FAST, true, out)) {
        fprintf(stderr, "[SSOptimizer] texcompress: 自测失败 format=BC1 useAlpha\n");
        return false;
    }
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_github_kasuminova_ssoptimizer_common_loading_NativeTextureCompressor_nativeIsSupported(
        JNIEnv*, jclass) {
    ssotex::initEncoders();
    return selfTest() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_github_kasuminova_ssoptimizer_common_loading_NativeTextureCompressor_nativeCompress(
        JNIEnv* env, jclass, jint format, jobject rgbaPixels,
        jint width, jint height, jint mipLevels, jint quality, jboolean useAlpha) {
    if (rgbaPixels == nullptr) {
        fprintf(stderr, "[SSOptimizer] texcompress: rgbaPixels 为 null\n");
        return nullptr;
    }
    const auto* pixels = static_cast<const uint8_t*>(env->GetDirectBufferAddress(rgbaPixels));
    if (pixels == nullptr) {
        fprintf(stderr, "[SSOptimizer] texcompress: rgbaPixels 非 direct buffer\n");
        return nullptr;
    }
    const jlong capacity = env->GetDirectBufferCapacity(rgbaPixels);
    if (width < 1 || height < 1
            || capacity < static_cast<jlong>(width) * height * 4) {
        fprintf(stderr, "[SSOptimizer] texcompress: 缓冲容量不足 capacity=%lld w=%d h=%d\n",
                static_cast<long long>(capacity), width, height);
        return nullptr;
    }

    std::vector<uint8_t> container;
    try {
        if (!ssotex::compressContainer(format, pixels, width, height, mipLevels, quality,
                                       useAlpha == JNI_TRUE, container)) {
            return nullptr; // core 内已记日志
        }
    } catch (...) {
        // 防御：C++ 异常不得越 JNI 边界
        fprintf(stderr, "[SSOptimizer] texcompress: 未捕获的 C++ 异常 format=%d %dx%d\n",
                format, width, height);
        return nullptr;
    }

    if (container.size() > static_cast<size_t>(INT32_MAX)) {
        fprintf(stderr, "[SSOptimizer] texcompress: 容器过大 %zu 字节\n", container.size());
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(container.size()));
    if (result == nullptr) {
        fprintf(stderr, "[SSOptimizer] texcompress: NewByteArray 失败（%zu 字节，JVM 已挂 OOM）\n",
                container.size());
        return nullptr;
    }
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(container.size()),
                            reinterpret_cast<const jbyte*>(container.data()));
    return result;
}

} // extern "C"
