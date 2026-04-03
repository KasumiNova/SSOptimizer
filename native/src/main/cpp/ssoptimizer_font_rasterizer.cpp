/**
 * TrueType 字体栅格化器（JNI 实现）。
 *
 * 对应 Java 类: NativeFontRasterizer
 * 使用 FreeType 库将 TTF/OTF 字形渲染为灰度位图，
 * 支持 SDF 模式和子像素反锯齿。
 */
#include "github_kasuminova_ssoptimizer_common_font_NativeFontRasterizer.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <vector>

#include <jni.h>

#if defined(SSOPTIMIZER_HAVE_FREETYPE) && __has_include(<ft2build.h>)
#include <ft2build.h>
#include FT_FREETYPE_H
#define SSOPTIMIZER_NATIVE_FREETYPE_AVAILABLE 1
#else
#define SSOPTIMIZER_NATIVE_FREETYPE_AVAILABLE 0
#endif

namespace {

constexpr const char* NATIVE_GLYPH_BITMAP_CLASS = "github/kasuminova/ssoptimizer/common/font/NativeGlyphBitmap";

#if SSOPTIMIZER_NATIVE_FREETYPE_AVAILABLE
struct NativeFaceHandle {
    FT_Library library = nullptr;
    FT_Face    face    = nullptr;
    bool       antiAlias = true;
    bool       forceAutoHint = false;
    bool       embeddedBitmaps = false;
    int        hintMode  = 0;
};

int rounded26_6(const FT_Pos value) {
    if (value >= 0) {
        return static_cast<int>((value + 32) >> 6);
    }
    return -static_cast<int>(((-value) + 32) >> 6);
}

FT_Int32 freetypeLoadFlags(const int hintMode,
                           const bool forceAutoHint,
                           const bool antiAlias,
                           const bool embeddedBitmaps) {
    FT_Int32 loadFlags = FT_LOAD_DEFAULT;
    if (forceAutoHint) {
        loadFlags |= FT_LOAD_FORCE_AUTOHINT;
    }
    if (!embeddedBitmaps) {
        loadFlags |= FT_LOAD_NO_BITMAP;
    }

    switch (hintMode) {
        case 1:
            loadFlags |= FT_LOAD_TARGET_LIGHT;
            break;
        case 2:
            loadFlags |= FT_LOAD_TARGET_NORMAL;
            break;
        case 3:
            loadFlags |= FT_LOAD_TARGET_MONO;
            break;
        case 4:
            loadFlags |= FT_LOAD_NO_HINTING;
            break;
        default:
            loadFlags |= antiAlias ? FT_LOAD_TARGET_LIGHT : FT_LOAD_TARGET_MONO;
            break;
    }
    return loadFlags;
}

FT_Render_Mode freetypeRenderMode(const int hintMode,
                                  const bool antiAlias) {
    if (!antiAlias || hintMode == 3) {
        return FT_RENDER_MODE_MONO;
    }
    return FT_RENDER_MODE_NORMAL;
}

bool initFace(const char* fontPath,
              const float pixelSize,
              const int hintMode,
              const bool forceAutoHint,
              const bool antiAlias,
              const bool embeddedBitmaps,
              NativeFaceHandle& out) {
    if (fontPath == nullptr || *fontPath == '\0' || !std::isfinite(pixelSize) || pixelSize <= 0.0f) {
        return false;
    }

    if (FT_Init_FreeType(&out.library) != 0) {
        return false;
    }
    if (FT_New_Face(out.library, fontPath, 0, &out.face) != 0) {
        FT_Done_FreeType(out.library);
        out.library = nullptr;
        return false;
    }

    FT_Select_Charmap(out.face, FT_ENCODING_UNICODE);
    const FT_F26Dot6 charHeight = static_cast<FT_F26Dot6>(std::lround(pixelSize * 64.0f));
    if (FT_Set_Char_Size(out.face, 0, std::max<FT_F26Dot6>(64, charHeight), 72, 72) != 0) {
        FT_Done_Face(out.face);
        FT_Done_FreeType(out.library);
        out.face = nullptr;
        out.library = nullptr;
        return false;
    }

    out.antiAlias = antiAlias;
    out.forceAutoHint = forceAutoHint;
    out.embeddedBitmaps = embeddedBitmaps;
    out.hintMode = hintMode;
    return true;
}

void destroyFaceHandle(NativeFaceHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    if (handle->face != nullptr) {
        FT_Done_Face(handle->face);
        handle->face = nullptr;
    }
    if (handle->library != nullptr) {
        FT_Done_FreeType(handle->library);
        handle->library = nullptr;
    }
    delete handle;
}

jobject createGlyphBitmap(JNIEnv* env,
                          const int width,
                          const int height,
                          jintArray argbPixels,
                          const int xOffset,
                          const int yOffset,
                          const int xAdvance) {
    jclass glyphClass = env->FindClass(NATIVE_GLYPH_BITMAP_CLASS);
    if (glyphClass == nullptr) {
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(glyphClass, "<init>", "(II[IIII)V");
    if (constructor == nullptr) {
        return nullptr;
    }

    return env->NewObject(glyphClass, constructor,
            static_cast<jint>(width),
            static_cast<jint>(height),
            argbPixels,
            static_cast<jint>(xOffset),
            static_cast<jint>(yOffset),
            static_cast<jint>(xAdvance));
}

jobject rasterizeGlyph(JNIEnv* env,
                       NativeFaceHandle* handle,
                       const jint codePoint,
                       const jint baseline) {
    if (handle == nullptr || handle->face == nullptr) {
        return nullptr;
    }

    const FT_Int32 loadFlags = freetypeLoadFlags(
            handle->hintMode,
            handle->forceAutoHint,
            handle->antiAlias,
            handle->embeddedBitmaps
    );
    if (FT_Load_Char(handle->face, static_cast<FT_ULong>(codePoint), loadFlags) != 0) {
        return nullptr;
    }

    FT_GlyphSlot slot = handle->face->glyph;
    const FT_Render_Mode renderMode = freetypeRenderMode(handle->hintMode, handle->antiAlias);
    if (slot->format != FT_GLYPH_FORMAT_BITMAP) {
        if (FT_Render_Glyph(slot, renderMode) != 0) {
            return nullptr;
        }
    }

    const FT_Bitmap& bitmap = slot->bitmap;
    const int width = static_cast<int>(bitmap.width);
    const int height = static_cast<int>(bitmap.rows);
    const int xOffset = static_cast<int>(slot->bitmap_left);
    const int yOffset = static_cast<int>(baseline - slot->bitmap_top);
    const int xAdvance = std::max(0, rounded26_6(slot->advance.x));

    if (width <= 0 || height <= 0 || bitmap.buffer == nullptr) {
        return createGlyphBitmap(env, 0, 0, nullptr, xOffset, yOffset, xAdvance);
    }

    const size_t pixelCount = static_cast<size_t>(width) * static_cast<size_t>(height);
    std::vector<jint> pixels(pixelCount, 0);

    const int pitch = std::abs(bitmap.pitch);
    for (int y = 0; y < height; y++) {
        const unsigned char* row = bitmap.buffer + static_cast<size_t>(y) * static_cast<size_t>(pitch);
        for (int x = 0; x < width; x++) {
            unsigned int alpha = 0;
            if (bitmap.pixel_mode == FT_PIXEL_MODE_MONO) {
                const unsigned char byteValue = row[x >> 3];
                const unsigned char bitMask = static_cast<unsigned char>(0x80u >> (x & 7));
                alpha = (byteValue & bitMask) != 0 ? 255u : 0u;
            } else {
                alpha = row[x];
            }
            pixels[static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)] =
                    static_cast<jint>((alpha << 24) | 0x00FFFFFFu);
        }
    }

    jintArray pixelArray = env->NewIntArray(static_cast<jsize>(pixelCount));
    if (pixelArray == nullptr) {
        return nullptr;
    }
    env->SetIntArrayRegion(pixelArray, 0, static_cast<jsize>(pixelCount), pixels.data());
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    return createGlyphBitmap(env, width, height, pixelArray, xOffset, yOffset, xAdvance);
}
#endif

} // namespace

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.font.NativeFontRasterizer#nativeIsAvailable()
 *
 * @param env JNI 环境
 * @param clazz Java 类对象（未使用）
 * @return FreeType 原生字体栅格化后端是否可用
 *
 * 内存管理：仅做可用性探测；若初始化 FreeType 成功会在返回前释放。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_github_kasuminova_ssoptimizer_common_font_NativeFontRasterizer_nativeIsAvailable(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
#if SSOPTIMIZER_NATIVE_FREETYPE_AVAILABLE
    FT_Library library = nullptr;
    const FT_Error status = FT_Init_FreeType(&library);
    if (status == 0 && library != nullptr) {
        FT_Done_FreeType(library);
        return JNI_TRUE;
    }
    return JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.font.NativeFontRasterizer#nativeCreateFace(String, float, int, boolean, boolean, boolean)
 *
 * @param env JNI 环境
 * @param clazz Java 类对象（未使用）
 * @param fontPath 字体文件路径
 * @param pixelSize 目标像素字号
 * @param hintMode Hinting 模式
 * @param forceAutoHint 是否强制自动 hint
 * @param antiAlias 是否启用抗锯齿
 * @param embeddedBitmaps 是否允许使用内嵌位图
 * @return NativeFaceHandle 指针编码后的 jlong；失败返回 0
 *
 * 内存管理：成功时由 Java 层后续调用 nativeDestroyFace 释放；失败路径在原生侧自行清理。
 */
extern "C" JNIEXPORT jlong JNICALL
Java_github_kasuminova_ssoptimizer_common_font_NativeFontRasterizer_nativeCreateFace(JNIEnv* env,
                                                                              jclass clazz,
                                                                              jstring fontPath,
                                                                              jfloat pixelSize,
                                                                              jint hintMode,
                                                                              jboolean forceAutoHint,
                                                                              jboolean antiAlias,
                                                                              jboolean embeddedBitmaps) {
    (void) clazz;
#if SSOPTIMIZER_NATIVE_FREETYPE_AVAILABLE
    if (fontPath == nullptr) {
        return 0L;
    }

    const char* pathChars = env->GetStringUTFChars(fontPath, nullptr);
    if (pathChars == nullptr) {
        return 0L;
    }

    auto* handle = new NativeFaceHandle();
    const bool initialized = initFace(
            pathChars,
            pixelSize,
            static_cast<int>(hintMode),
            forceAutoHint == JNI_TRUE,
            antiAlias == JNI_TRUE,
            embeddedBitmaps == JNI_TRUE,
            *handle
    );
    env->ReleaseStringUTFChars(fontPath, pathChars);

    if (!initialized) {
        destroyFaceHandle(handle);
        return 0L;
    }
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(handle));
#else
    (void) env;
    (void) fontPath;
    (void) pixelSize;
    (void) hintMode;
    (void) forceAutoHint;
    (void) antiAlias;
    (void) embeddedBitmaps;
    return 0L;
#endif
}

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.font.NativeFontRasterizer#nativeRasterizeGlyph(long, int, int)
 *
 * @param env JNI 环境
 * @param clazz Java 类对象（未使用）
 * @param faceHandle Java 层持有的字体句柄
 * @param codePoint 要栅格化的 Unicode code point
 * @param baseline 基线位置
 * @return NativeGlyphBitmap；失败时返回 null
 *
 * 内存管理：返回对象由 JVM 管理；不转移底层 FreeType 句柄所有权。
 */
extern "C" JNIEXPORT jobject JNICALL
Java_github_kasuminova_ssoptimizer_common_font_NativeFontRasterizer_nativeRasterizeGlyph(JNIEnv* env,
                                                                                  jclass clazz,
                                                                                  jlong faceHandle,
                                                                                  jint codePoint,
                                                                                  jint baseline) {
    (void) clazz;
#if SSOPTIMIZER_NATIVE_FREETYPE_AVAILABLE
    auto* handle = reinterpret_cast<NativeFaceHandle*>(static_cast<std::uintptr_t>(faceHandle));
    return rasterizeGlyph(env, handle, codePoint, baseline);
#else
    (void) env;
    (void) faceHandle;
    (void) codePoint;
    (void) baseline;
    return nullptr;
#endif
}

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.font.NativeFontRasterizer#nativeDestroyFace(long)
 *
 * @param env JNI 环境
 * @param clazz Java 类对象（未使用）
 * @param faceHandle Java 层持有的字体句柄
 * @return 无返回值
 *
 * 内存管理：释放由 nativeCreateFace 分配的 NativeFaceHandle 及其关联 FreeType 资源。
 */
extern "C" JNIEXPORT void JNICALL
Java_github_kasuminova_ssoptimizer_common_font_NativeFontRasterizer_nativeDestroyFace(JNIEnv* env,
                                                                               jclass clazz,
                                                                               jlong faceHandle) {
    (void) env;
    (void) clazz;
#if SSOPTIMIZER_NATIVE_FREETYPE_AVAILABLE
    auto* handle = reinterpret_cast<NativeFaceHandle*>(static_cast<std::uintptr_t>(faceHandle));
    destroyFaceHandle(handle);
#else
    (void) faceHandle;
#endif
}
