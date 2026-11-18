#include "github_kasuminova_ssoptimizer_common_font_NativeFontRasterizer.h"
#include "github_kasuminova_ssoptimizer_common_render_runtime_NativeRuntime.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <exception>
#include <filesystem>
#include <limits>
#include <memory>
#include <string>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#endif

#include <jni.h>

#if defined(SSOPTIMIZER_HAVE_FREETYPE) && __has_include(<ft2build.h>)
#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_GLYPH_H
#include FT_STROKER_H
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
    // FT_Stroker 非线程安全，Java 调用方必须按 face 句柄同步访问。
    FT_Stroker stroker = nullptr;
    bool       antiAlias = true;
    bool       forceAutoHint = false;
    bool       embeddedBitmaps = false;
    int        hintMode  = 0;
#ifdef _WIN32
    const FT_Byte* mappedFont = nullptr;
#endif

    ~NativeFaceHandle() {
        if (stroker) FT_Stroker_Done(stroker);
        if (face) FT_Done_Face(face);
        if (library) FT_Done_FreeType(library);
#ifdef _WIN32
        if (mappedFont) UnmapViewOfFile(mappedFont);
#endif
    }
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

std::filesystem::path fontFilePath(JNIEnv* env, jstring value) {
    const jsize length = env->GetStringLength(value);
#ifdef _WIN32
    std::wstring path(length, L'\0');
#else
    std::u16string path(length, u'\0');
#endif
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (!chars) return {};
    std::copy_n(chars, length, path.begin());
    env->ReleaseStringChars(value, chars);
    if (path.find('\0') != path.npos) return {};
#ifdef _WIN32
    // Java 提供规范化绝对路径；长路径需要保留驱动器或 UNC 共享根。
    if (path.size() >= MAX_PATH && !path.starts_with(L"\\\\?\\") && !path.starts_with(L"\\\\.\\")) {
        if (path.starts_with(L"\\\\")) path.replace(0, 2, L"\\\\?\\UNC\\");
        else path.insert(0, L"\\\\?\\");
    }
#endif
    return std::filesystem::path(std::move(path));
}

bool fontFailure(const std::filesystem::path& path, const char* operation, long long code) {
    const auto utf8 = path.u8string();
    std::fprintf(stderr, "[SSOptimizer] font: %s failed (code=%lld), path=%s\n",
                 operation, code, reinterpret_cast<const char*>(utf8.c_str()));
    return false;
}

bool openFace(const std::filesystem::path& path, NativeFaceHandle& out) {
#ifdef _WIN32
    HANDLE raw = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_DELETE, nullptr,
                             OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (raw == INVALID_HANDLE_VALUE) return fontFailure(path, "CreateFileW", GetLastError());
    using Handle = std::unique_ptr<void, decltype(&CloseHandle)>;
    Handle file(raw, CloseHandle);
    LARGE_INTEGER size;
    if (!GetFileSizeEx(file.get(), &size)) return fontFailure(path, "GetFileSizeEx", GetLastError());
    if (size.QuadPart <= 0 || size.QuadPart > std::numeric_limits<FT_Long>::max()) {
        return fontFailure(path, "FreeType file size in bytes", size.QuadPart);
    }
    Handle mapping(CreateFileMappingW(file.get(), nullptr, PAGE_READONLY, 0, 0, nullptr), CloseHandle);
    if (!mapping) return fontFailure(path, "CreateFileMappingW", GetLastError());
    out.mappedFont = static_cast<const FT_Byte*>(MapViewOfFile(mapping.get(), FILE_MAP_READ, 0, 0, 0));
    if (!out.mappedFont) return fontFailure(path, "MapViewOfFile", GetLastError());
    const FT_Error status = FT_New_Memory_Face(out.library, out.mappedFont,
                                               static_cast<FT_Long>(size.QuadPart), 0, &out.face);
    return status == 0 || fontFailure(path, "FT_New_Memory_Face", status);
#else
    const FT_Error status = FT_New_Face(out.library, path.c_str(), 0, &out.face);
    return status == 0 || fontFailure(path, "FT_New_Face", status);
#endif
}

bool initFace(const std::filesystem::path& fontPath,
              const float pixelSize,
              const int hintMode,
              const bool forceAutoHint,
              const bool antiAlias,
              const bool embeddedBitmaps,
              NativeFaceHandle& out) {
    if (fontPath.empty() || !std::isfinite(pixelSize) || pixelSize <= 0.0f) {
        return fontFailure(fontPath, "invalid font path or pixel size", FT_Err_Invalid_Argument);
    }
    FT_Error status = FT_Init_FreeType(&out.library);
    if (status != 0) return fontFailure(fontPath, "FT_Init_FreeType", status);
    if (!openFace(fontPath, out)) return false;

    FT_Select_Charmap(out.face, FT_ENCODING_UNICODE);
    const FT_F26Dot6 charHeight = static_cast<FT_F26Dot6>(std::lround(pixelSize * 64.0f));
    status = FT_Set_Char_Size(out.face, 0, std::max<FT_F26Dot6>(64, charHeight), 72, 72);
    if (status != 0) return fontFailure(fontPath, "FT_Set_Char_Size", status);

    out.antiAlias = antiAlias;
    out.forceAutoHint = forceAutoHint;
    out.embeddedBitmaps = embeddedBitmaps;
    out.hintMode = hintMode;

    status = FT_Stroker_New(out.library, &out.stroker);
    if (status != 0) {
        fontFailure(fontPath, "FT_Stroker_New", status);
        out.stroker = nullptr;
    }
    return true;
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

struct GrayBitmap {
    int left = 0;
    int top = 0;
    int width = 0;
    int height = 0;
    std::vector<unsigned char> alpha;

    bool empty() const {
        return width <= 0 || height <= 0 || alpha.empty();
    }
};

GrayBitmap ftBitmapToGray(const FT_Bitmap& bitmap,
                          const int left,
                          const int top) {
    GrayBitmap out;
    out.left = left;
    out.top = top;
    out.width = static_cast<int>(bitmap.width);
    out.height = static_cast<int>(bitmap.rows);
    if (out.width <= 0 || out.height <= 0 || bitmap.buffer == nullptr) {
        out.width = 0;
        out.height = 0;
        return out;
    }

    out.alpha.assign(static_cast<size_t>(out.width) * static_cast<size_t>(out.height), 0);
    const int pitch = std::abs(bitmap.pitch);
    for (int y = 0; y < out.height; y++) {
        const unsigned char* row = bitmap.buffer + static_cast<size_t>(y) * static_cast<size_t>(pitch);
        for (int x = 0; x < out.width; x++) {
            unsigned int alpha = 0;
            if (bitmap.pixel_mode == FT_PIXEL_MODE_MONO) {
                const unsigned char byteValue = row[x >> 3];
                const unsigned char bitMask = static_cast<unsigned char>(0x80u >> (x & 7));
                alpha = (byteValue & bitMask) != 0 ? 255u : 0u;
            } else {
                alpha = row[x];
            }
            out.alpha[static_cast<size_t>(y) * static_cast<size_t>(out.width) + static_cast<size_t>(x)] =
                    static_cast<unsigned char>(alpha);
        }
    }
    return out;
}

jobject grayBitmapToJava(JNIEnv* env,
                         const GrayBitmap& bitmap,
                         const int baseline,
                         const int xAdvance) {
    const int xOffset = bitmap.left;
    const int yOffset = baseline - bitmap.top;

    if (bitmap.empty()) {
        return createGlyphBitmap(env, 0, 0, nullptr, xOffset, yOffset, xAdvance);
    }

    const size_t pixelCount = static_cast<size_t>(bitmap.width) * static_cast<size_t>(bitmap.height);
    std::vector<jint> pixels(pixelCount, 0);
    for (size_t i = 0; i < pixelCount; i++) {
        pixels[i] = static_cast<jint>((static_cast<unsigned int>(bitmap.alpha[i]) << 24) | 0x00FFFFFFu);
    }

    jintArray pixelArray = env->NewIntArray(static_cast<jsize>(pixelCount));
    if (pixelArray == nullptr) {
        return nullptr;
    }
    env->SetIntArrayRegion(pixelArray, 0, static_cast<jsize>(pixelCount), pixels.data());
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    return createGlyphBitmap(env, bitmap.width, bitmap.height, pixelArray, xOffset, yOffset, xAdvance);
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

    const int xAdvance = std::max(0, rounded26_6(slot->advance.x));
    const GrayBitmap fill = ftBitmapToGray(slot->bitmap, slot->bitmap_left, slot->bitmap_top);
    return grayBitmapToJava(env, fill, baseline, xAdvance);
}

jobject rasterizeGlyphStroked(JNIEnv* env,
                              NativeFaceHandle* handle,
                              const jint codePoint,
                              const jint baseline,
                              const jfloat strokeWidthPx) {
    if (handle == nullptr || handle->face == nullptr) {
        return nullptr;
    }
    if (!std::isfinite(strokeWidthPx) || strokeWidthPx <= 0.0f || handle->stroker == nullptr) {
        return rasterizeGlyph(env, handle, codePoint, baseline);
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

    // FT_Render_Glyph 会将 slot 转为位图，必须在渲染前提取独立轮廓副本。
    FT_Glyph outline = nullptr;
    if (FT_Get_Glyph(slot, &outline) != 0 || outline == nullptr
            || outline->format != FT_GLYPH_FORMAT_OUTLINE) {
        if (outline != nullptr) {
            FT_Done_Glyph(outline);
        }
        outline = nullptr;
    }

    const FT_Render_Mode renderMode = freetypeRenderMode(handle->hintMode, handle->antiAlias);
    if (slot->format != FT_GLYPH_FORMAT_BITMAP) {
        if (FT_Render_Glyph(slot, renderMode) != 0) {
            if (outline != nullptr) {
                FT_Done_Glyph(outline);
            }
            return nullptr;
        }
    }
    const int xAdvance = std::max(0, rounded26_6(slot->advance.x));
    const GrayBitmap fill = ftBitmapToGray(slot->bitmap, slot->bitmap_left, slot->bitmap_top);

    if (outline == nullptr) {
        return grayBitmapToJava(env, fill, baseline, xAdvance);
    }

    FT_Stroker_Set(handle->stroker,
            static_cast<FT_Fixed>(std::lround(strokeWidthPx * 64.0)),
            FT_STROKER_LINECAP_ROUND,
            FT_STROKER_LINEJOIN_ROUND,
            0);
    if (FT_Glyph_StrokeBorder(&outline, handle->stroker, false, false) != 0) {
        FT_Done_Glyph(outline);
        return grayBitmapToJava(env, fill, baseline, xAdvance);
    }
    // destroy=false 保留原轮廓的显式释放责任，避开不同 FreeType 版本对失败路径所有权的含混语义。
    FT_Glyph strokeBitmap = outline;
    if (FT_Glyph_To_Bitmap(&strokeBitmap, renderMode, nullptr, false) != 0) {
        FT_Done_Glyph(outline);
        return grayBitmapToJava(env, fill, baseline, xAdvance);
    }
    FT_Done_Glyph(outline);

    const auto* strokedGlyph = reinterpret_cast<FT_BitmapGlyph>(strokeBitmap);
    const GrayBitmap stroke = ftBitmapToGray(strokedGlyph->bitmap, strokedGlyph->left, strokedGlyph->top);
    FT_Done_Glyph(strokeBitmap);

    if (stroke.empty()) {
        return grayBitmapToJava(env, fill, baseline, xAdvance);
    }

    const int unionLeft = fill.empty() ? stroke.left : std::min(fill.left, stroke.left);
    const int unionTop = fill.empty() ? stroke.top : std::max(fill.top, stroke.top);
    const int unionRight = fill.empty()
            ? stroke.left + stroke.width
            : std::max(fill.left + fill.width, stroke.left + stroke.width);
    const int unionBottom = fill.empty()
            ? stroke.top - stroke.height
            : std::min(fill.top - fill.height, stroke.top - stroke.height);

    GrayBitmap merged;
    merged.left = unionLeft;
    merged.top = unionTop;
    merged.width = std::max(0, unionRight - unionLeft);
    merged.height = std::max(0, unionTop - unionBottom);
    // alpha 尚未分配时 merged.empty() 恒为真，不能用其判断尺寸是否有效。
    if (merged.width > 0 && merged.height > 0) {
        merged.alpha.assign(
                static_cast<size_t>(merged.width) * static_cast<size_t>(merged.height), 0);
    }

    const auto blendInto = [&merged](const GrayBitmap& src) {
        if (src.empty()) {
            return;
        }
        for (int sy = 0; sy < src.height; sy++) {
            const int dy = merged.top - (src.top - sy);
            if (dy < 0 || dy >= merged.height) {
                continue;
            }
            const int dxStart = src.left - merged.left;
            for (int sx = 0; sx < src.width; sx++) {
                const int dx = dxStart + sx;
                if (dx < 0 || dx >= merged.width) {
                    continue;
                }
                unsigned char& dst = merged.alpha[
                        static_cast<size_t>(dy) * static_cast<size_t>(merged.width) + static_cast<size_t>(dx)];
                dst = std::max(dst, src.alpha[
                        static_cast<size_t>(sy) * static_cast<size_t>(src.width) + static_cast<size_t>(sx)]);
            }
        }
    };
    blendInto(fill);
    blendInto(stroke);

    return grayBitmapToJava(env, merged, baseline, xAdvance);
}
#endif

}

extern "C" JNIEXPORT jint JNICALL
Java_github_kasuminova_ssoptimizer_common_render_runtime_NativeRuntime_nativeFontAbiVersion(JNIEnv*, jclass) {
    return static_cast<jint>(github_kasuminova_ssoptimizer_common_render_runtime_NativeRuntime_NATIVE_ABI_VERSION);
}

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

    try {
        const auto path = fontFilePath(env, fontPath);
        if (env->ExceptionCheck()) return 0L;
        auto handle = std::make_unique<NativeFaceHandle>();
        if (!initFace(path, pixelSize, hintMode, forceAutoHint == JNI_TRUE, antiAlias == JNI_TRUE,
                      embeddedBitmaps == JNI_TRUE, *handle)) return 0L;
        return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(handle.release()));
    } catch (const std::exception& error) {
        std::fprintf(stderr, "[SSOptimizer] font: createFace failed: %s\n", error.what());
        return 0L;
    }
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

extern "C" JNIEXPORT jobject JNICALL
Java_github_kasuminova_ssoptimizer_common_font_NativeFontRasterizer_nativeRasterizeGlyphStroked(JNIEnv* env,
                                                                                         jclass clazz,
                                                                                         jlong faceHandle,
                                                                                         jint codePoint,
                                                                                         jint baseline,
                                                                                         jfloat strokeWidthPx) {
    (void) clazz;
#if SSOPTIMIZER_NATIVE_FREETYPE_AVAILABLE
    auto* handle = reinterpret_cast<NativeFaceHandle*>(static_cast<std::uintptr_t>(faceHandle));
    return rasterizeGlyphStroked(env, handle, codePoint, baseline, strokeWidthPx);
#else
    (void) env;
    (void) faceHandle;
    (void) codePoint;
    (void) baseline;
    (void) strokeWidthPx;
    return nullptr;
#endif
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_github_kasuminova_ssoptimizer_common_font_NativeFontRasterizer_nativeRasterizeGlyphs(JNIEnv* env,
                                                                                   jclass clazz,
                                                                                   jlong faceHandle,
                                                                                   jintArray codePoints,
                                                                                   jint baseline,
                                                                                   jfloat strokeWidthPx) {
    (void) clazz;
#if SSOPTIMIZER_NATIVE_FREETYPE_AVAILABLE
    if (codePoints == nullptr) {
        return nullptr;
    }

    auto* handle = reinterpret_cast<NativeFaceHandle*>(static_cast<std::uintptr_t>(faceHandle));
    if (handle == nullptr || handle->face == nullptr) {
        return nullptr;
    }

    const jsize count = env->GetArrayLength(codePoints);
    jclass glyphClass = env->FindClass(NATIVE_GLYPH_BITMAP_CLASS);
    if (glyphClass == nullptr) {
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(count, glyphClass, nullptr);
    if (result == nullptr) {
        return nullptr;
    }

    jint* codePointElements = env->GetIntArrayElements(codePoints, nullptr);
    if (codePointElements == nullptr) {
        return nullptr;
    }

    const bool stroked = std::isfinite(strokeWidthPx) && strokeWidthPx > 0.0f;
    for (jsize i = 0; i < count; i++) {
        jobject bitmap = stroked
                ? rasterizeGlyphStroked(env, handle, codePointElements[i], baseline, strokeWidthPx)
                : rasterizeGlyph(env, handle, codePointElements[i], baseline);
        if (bitmap == nullptr) {
            continue;
        }
        env->SetObjectArrayElement(result, i, bitmap);
        env->DeleteLocalRef(bitmap);
        if (env->ExceptionCheck()) {
            env->ReleaseIntArrayElements(codePoints, codePointElements, JNI_ABORT);
            return nullptr;
        }
    }

    env->ReleaseIntArrayElements(codePoints, codePointElements, JNI_ABORT);
    return result;
#else
    (void) env;
    (void) faceHandle;
    (void) codePoints;
    (void) baseline;
    (void) strokeWidthPx;
    return nullptr;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_github_kasuminova_ssoptimizer_common_font_NativeFontRasterizer_nativeHasGlyph(JNIEnv* env,
                                                                            jclass clazz,
                                                                            jlong faceHandle,
                                                                            jint codePoint) {
    (void) env;
    (void) clazz;
#if SSOPTIMIZER_NATIVE_FREETYPE_AVAILABLE
    if (codePoint <= 0) {
        return JNI_FALSE;
    }
    auto* handle = reinterpret_cast<NativeFaceHandle*>(static_cast<std::uintptr_t>(faceHandle));
    if (handle == nullptr || handle->face == nullptr) {
        return JNI_FALSE;
    }
    return FT_Get_Char_Index(handle->face, static_cast<FT_ULong>(codePoint)) != 0 ? JNI_TRUE : JNI_FALSE;
#else
    (void) faceHandle;
    (void) codePoint;
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_github_kasuminova_ssoptimizer_common_font_NativeFontRasterizer_nativeDestroyFace(JNIEnv* env,
                                                                               jclass clazz,
                                                                               jlong faceHandle) {
    (void) env;
    (void) clazz;
#if SSOPTIMIZER_NATIVE_FREETYPE_AVAILABLE
    auto* handle = reinterpret_cast<NativeFaceHandle*>(static_cast<std::uintptr_t>(faceHandle));
    delete handle;
#else
    (void) faceHandle;
#endif
}
