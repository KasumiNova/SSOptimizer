/**
 * 原生 PNG 解码器（JNI 实现）。
 *
 * 对应 Java 类: NativePngDecoder
 * 使用 libpng 解码 PNG 图片并将 RGBA 像素转换为 Java int[] ARGB 格式，
 * 在多线程环境下安全调用，可显著提升资源加载速度。
 */
#include <cstring>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

#include <jni.h>

#if defined(SSOPTIMIZER_HAVE_LIBPNG) && __has_include(<png.h>)
#include <png.h>
#define SSOPTIMIZER_NATIVE_LIBPNG_AVAILABLE 1
#else
#define SSOPTIMIZER_NATIVE_LIBPNG_AVAILABLE 0
#endif

namespace {

constexpr const char* DECODED_IMAGE_CLASS = "github/kasuminova/ssoptimizer/common/loading/NativeDecodedImage";

void throwJavaException(JNIEnv* env, const char* className, const std::string& message) {
    jclass exceptionClass = env->FindClass(className);
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message.c_str());
    }
}

#if SSOPTIMIZER_NATIVE_LIBPNG_AVAILABLE
struct PngErrorState {
    std::string message;
};

struct MemoryReader {
    png_const_bytep data;
    const size_t size;
    size_t offset;
};

void pngReadCallback(png_structp pngPtr, png_bytep outBytes, png_size_t byteCountToRead) {
    auto* reader = static_cast<MemoryReader*>(png_get_io_ptr(pngPtr));
    if (reader == nullptr || reader->offset + byteCountToRead > reader->size) {
        png_error(pngPtr, "Unexpected end of PNG data");
        return;
    }

    std::memcpy(outBytes, reader->data + reader->offset, byteCountToRead);
    reader->offset += byteCountToRead;
}

void pngErrorCallback(png_structp pngPtr, png_const_charp errorMessage) {
    auto* errorState = static_cast<PngErrorState*>(png_get_error_ptr(pngPtr));
    if (errorState != nullptr && errorMessage != nullptr) {
        errorState->message = errorMessage;
    }
    longjmp(png_jmpbuf(pngPtr), 1);
}

void pngWarningCallback(png_structp, png_const_charp) {
}

jobject createDecodedImage(JNIEnv* env,
                           const jint width,
                           const jint height,
                           const jintArray pixelsArray) {
    jclass decodedImageClass = env->FindClass(DECODED_IMAGE_CLASS);
    if (decodedImageClass == nullptr) {
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(decodedImageClass, "<init>", "(II[I)V");
    if (constructor == nullptr) {
        return nullptr;
    }

    return env->NewObject(decodedImageClass, constructor, width, height, pixelsArray);
}

jobject decodePng(JNIEnv* env, const jbyte* imageBytes, const jsize imageLength) {
    if (imageBytes == nullptr || imageLength <= 0) {
        throwJavaException(env, "java/io/IOException", "PNG byte array is empty");
        return nullptr;
    }

    PngErrorState errorState;
    png_structp pngPtr = png_create_read_struct(PNG_LIBPNG_VER_STRING, &errorState, pngErrorCallback, pngWarningCallback);
    if (pngPtr == nullptr) {
        throwJavaException(env, "java/io/IOException", "Failed to create libpng read struct");
        return nullptr;
    }

    png_infop infoPtr = png_create_info_struct(pngPtr);
    if (infoPtr == nullptr) {
        png_destroy_read_struct(&pngPtr, nullptr, nullptr);
        throwJavaException(env, "java/io/IOException", "Failed to create libpng info struct");
        return nullptr;
    }

    if (setjmp(png_jmpbuf(pngPtr)) != 0) {
        png_destroy_read_struct(&pngPtr, &infoPtr, nullptr);
        throwJavaException(
                env,
                "java/io/IOException",
                errorState.message.empty() ? "libpng decode failure" : errorState.message
        );
        return nullptr;
    }

    MemoryReader reader{
            reinterpret_cast<png_const_bytep>(imageBytes),
            static_cast<size_t>(imageLength),
            0
    };
    png_set_read_fn(pngPtr, &reader, pngReadCallback);
    png_read_info(pngPtr, infoPtr);

    png_uint_32 width = 0;
    png_uint_32 height = 0;
    int bitDepth = 0;
    int colorType = 0;
    int interlaceType = 0;
    int compressionType = 0;
    int filterMethod = 0;
    png_get_IHDR(
            pngPtr,
            infoPtr,
            &width,
            &height,
            &bitDepth,
            &colorType,
            &interlaceType,
            &compressionType,
            &filterMethod
    );

    if (width == 0 || height == 0) {
        png_destroy_read_struct(&pngPtr, &infoPtr, nullptr);
        throwJavaException(env, "java/io/IOException", "PNG has invalid dimensions");
        return nullptr;
    }
    if (width > static_cast<png_uint_32>(std::numeric_limits<jint>::max())
            || height > static_cast<png_uint_32>(std::numeric_limits<jint>::max())) {
        png_destroy_read_struct(&pngPtr, &infoPtr, nullptr);
        throwJavaException(env, "java/io/IOException", "PNG dimensions exceed Java limits");
        return nullptr;
    }

    if (bitDepth == 16) {
        png_set_strip_16(pngPtr);
    }
    if (colorType == PNG_COLOR_TYPE_PALETTE) {
        png_set_palette_to_rgb(pngPtr);
    }
    if (colorType == PNG_COLOR_TYPE_GRAY && bitDepth < 8) {
        png_set_expand_gray_1_2_4_to_8(pngPtr);
    }
    if (png_get_valid(pngPtr, infoPtr, PNG_INFO_tRNS) != 0) {
        png_set_tRNS_to_alpha(pngPtr);
    }
    if (colorType == PNG_COLOR_TYPE_RGB
            || colorType == PNG_COLOR_TYPE_GRAY
            || colorType == PNG_COLOR_TYPE_PALETTE) {
        png_set_filler(pngPtr, 0xFF, PNG_FILLER_AFTER);
    }
    if (colorType == PNG_COLOR_TYPE_GRAY || colorType == PNG_COLOR_TYPE_GRAY_ALPHA) {
        png_set_gray_to_rgb(pngPtr);
    }
    // Current native target is Linux x86_64. Writing BGRA bytes directly into the
    // little-endian Java int[] storage yields packed ARGB ints without an extra
    // post-decode channel shuffle or extra staging buffer.
    png_set_bgr(pngPtr);

    png_read_update_info(pngPtr, infoPtr);
    const png_size_t rowBytes = png_get_rowbytes(pngPtr, infoPtr);
    if (rowBytes < width * 4U) {
        png_destroy_read_struct(&pngPtr, &infoPtr, nullptr);
        throwJavaException(env, "java/io/IOException", "libpng returned an unexpected row stride");
        return nullptr;
    }

    const size_t pixelCount = static_cast<size_t>(width) * static_cast<size_t>(height);
    jintArray pixelsArray = env->NewIntArray(static_cast<jsize>(pixelCount));
    if (pixelsArray == nullptr) {
        png_destroy_read_struct(&pngPtr, &infoPtr, nullptr);
        return nullptr;
    }

    jint* pixels = env->GetIntArrayElements(pixelsArray, nullptr);
    if (pixels == nullptr) {
        png_destroy_read_struct(&pngPtr, &infoPtr, nullptr);
        return nullptr;
    }

    std::vector<png_bytep> rows(static_cast<size_t>(height));
    for (png_uint_32 y = 0; y < height; y++) {
        rows[y] = reinterpret_cast<png_bytep>(pixels) + static_cast<size_t>(y) * rowBytes;
    }

    png_read_image(pngPtr, rows.data());
    png_read_end(pngPtr, nullptr);
    png_destroy_read_struct(&pngPtr, &infoPtr, nullptr);

    env->ReleaseIntArrayElements(pixelsArray, pixels, 0);
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    return createDecodedImage(env, static_cast<jint>(width), static_cast<jint>(height), pixelsArray);
}

jobject benchmarkBridge(JNIEnv* env,
                        const jbyte* imageBytes,
                        const jsize imageLength,
                        const jint width,
                        const jint height) {
    if (imageBytes == nullptr || imageLength <= 0) {
        throwJavaException(env, "java/io/IOException", "PNG byte array is empty");
        return nullptr;
    }
    if (width <= 0 || height <= 0) {
        throwJavaException(env, "java/lang/IllegalArgumentException", "width and height must be positive");
        return nullptr;
    }

    const size_t pixelCount = static_cast<size_t>(width) * static_cast<size_t>(height);
    jintArray pixelsArray = env->NewIntArray(static_cast<jsize>(pixelCount));
    if (pixelsArray == nullptr) {
        return nullptr;
    }

    jint* pixels = env->GetIntArrayElements(pixelsArray, nullptr);
    if (pixels == nullptr) {
        return nullptr;
    }

    const auto first = static_cast<std::uint32_t>(static_cast<unsigned char>(imageBytes[0]));
    const auto last = static_cast<std::uint32_t>(static_cast<unsigned char>(imageBytes[imageLength - 1]));
    const std::uint32_t seed = (first << 16) ^ (last << 8) ^ static_cast<std::uint32_t>(imageLength);

    for (size_t i = 0; i < pixelCount; i++) {
        const std::uint32_t rgb = (seed + static_cast<std::uint32_t>(i * 2654435761u)) & 0x00FFFFFFu;
        pixels[i] = static_cast<jint>(0xFF000000u | rgb);
    }

    env->ReleaseIntArrayElements(pixelsArray, pixels, 0);
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    return createDecodedImage(env, width, height, pixelsArray);
}
#endif

} // namespace

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.loading.NativePngDecoder#nativeIsSupported()
 *
 * @param env JNI 环境
 * @param clazz Java 类对象（未使用）
 * @return 原生 PNG 解码后端是否可用
 *
 * 内存管理：不分配需要调用方释放的原生资源。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_github_kasuminova_ssoptimizer_common_loading_NativePngDecoder_nativeIsSupported(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
#if SSOPTIMIZER_NATIVE_LIBPNG_AVAILABLE
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.loading.NativePngDecoder#decodePng0(byte[])
 *
 * @param env JNI 环境
 * @param clazz Java 类对象（未使用）
 * @param imageBytes PNG 文件字节数组
 * @return 解码后的 NativeDecodedImage；失败时返回 null，并由 JNI 抛出 Java 异常
 *
 * 内存管理：通过 JNI 获取的字节数组元素在返回前释放；返回对象由 JVM 管理。
 */
extern "C" JNIEXPORT jobject JNICALL
Java_github_kasuminova_ssoptimizer_common_loading_NativePngDecoder_decodePng0(JNIEnv* env,
                                                                       jclass clazz,
                                                                       jbyteArray imageBytes) {
    (void) clazz;
#if SSOPTIMIZER_NATIVE_LIBPNG_AVAILABLE
    if (imageBytes == nullptr) {
        throwJavaException(env, "java/lang/NullPointerException", "imageBytes");
        return nullptr;
    }

    const jsize imageLength = env->GetArrayLength(imageBytes);
    if (imageLength <= 0) {
        throwJavaException(env, "java/io/IOException", "PNG byte array is empty");
        return nullptr;
    }

    jboolean isCopy = JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(imageBytes, &isCopy);
    if (bytes == nullptr) {
        throwJavaException(env, "java/lang/OutOfMemoryError", "Failed to access PNG byte array");
        return nullptr;
    }

    jobject decoded = decodePng(env, bytes, imageLength);
    env->ReleaseByteArrayElements(imageBytes, bytes, JNI_ABORT);
    return decoded;
#else
    (void) imageBytes;
    throwJavaException(env, "java/lang/UnsupportedOperationException", "Native PNG backend was built without libpng support");
    return nullptr;
#endif
}

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.loading.NativePngDecoder#benchmarkBridge0(byte[], int, int)
 *
 * @param env JNI 环境
 * @param clazz Java 类对象（未使用）
 * @param imageBytes PNG 文件字节数组
 * @param width 目标宽度
 * @param height 目标高度
 * @return 供基准测试使用的 NativeDecodedImage；失败时返回 null，并由 JNI 抛出 Java 异常
 *
 * 内存管理：通过 JNI 获取的字节数组元素在返回前释放；返回对象由 JVM 管理。
 */
extern "C" JNIEXPORT jobject JNICALL
Java_github_kasuminova_ssoptimizer_common_loading_NativePngDecoder_benchmarkBridge0(JNIEnv* env,
                                                                             jclass clazz,
                                                                             jbyteArray imageBytes,
                                                                             jint width,
                                                                             jint height) {
    (void) clazz;
#if SSOPTIMIZER_NATIVE_LIBPNG_AVAILABLE
    if (imageBytes == nullptr) {
        throwJavaException(env, "java/lang/NullPointerException", "imageBytes");
        return nullptr;
    }

    const jsize imageLength = env->GetArrayLength(imageBytes);
    if (imageLength <= 0) {
        throwJavaException(env, "java/io/IOException", "PNG byte array is empty");
        return nullptr;
    }

    jboolean isCopy = JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(imageBytes, &isCopy);
    if (bytes == nullptr) {
        throwJavaException(env, "java/lang/OutOfMemoryError", "Failed to access PNG byte array");
        return nullptr;
    }

    jobject decoded = benchmarkBridge(env, bytes, imageLength, width, height);
    env->ReleaseByteArrayElements(imageBytes, bytes, JNI_ABORT);
    return decoded;
#else
    (void) imageBytes;
    (void) width;
    (void) height;
    throwJavaException(env, "java/lang/UnsupportedOperationException", "Native PNG backend was built without libpng support");
    return nullptr;
#endif
}