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
    // 描边器：随 face 创建，供描边剪影栅格化复用。
    // 注意：FT_Stroker 非线程安全，Java 调用方必须按 face 句柄同步访问，本层不加锁。
    FT_Stroker stroker = nullptr;
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

    // 描边器创建失败不视为致命：描边剪影路径会在 stroker 为空时退化为纯填充
    if (FT_Stroker_New(out.library, &out.stroker) != 0) {
        out.stroker = nullptr;
    }
    return true;
}

void destroyFaceHandle(NativeFaceHandle* handle) {
    if (handle == nullptr) {
        return;
    }
    if (handle->stroker != nullptr) {
        FT_Stroker_Done(handle->stroker);
        handle->stroker = nullptr;
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

/**
 * 8-bit 灰度覆盖率位图（row-major），带 FreeType bearing（left/top，y 轴向上）。
 * 作为填充栅格化与描边外环合成的统一中间表示。
 */
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

/**
 * 将 FT_Bitmap 转换为 8-bit 灰度 GrayBitmap。
 * 支持 FT_PIXEL_MODE_GRAY（直接拷贝）与 FT_PIXEL_MODE_MONO（1-bit 展开为 0/255）。
 */
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

/**
 * 将 GrayBitmap 转换为 NativeGlyphBitmap Java 对象（白底 alpha：(alpha<<24)|0xFFFFFF）。
 * xOffset 取位图 left，yOffset = baseline - top；空位图输出 argbPixels=null 且尺寸为 0。
 */
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

/**
 * 描边剪影栅格化：填充字形 ∪ 轮廓向外扩张 strokeWidthPx 像素的外环。
 *
 * 流程：FT_Load_Char → 渲染填充字形并取其灰度位图与 advance →
 * FT_Get_Glyph 取轮廓副本 → FT_Stroker_Set（ROUND cap/join）→
 * FT_Glyph_StrokeBorder（仅外侧，不销毁原轮廓）→ FT_Glyph_To_Bitmap 得外环位图 →
 * 两张位图按各自 bearing 求包围盒并集，逐像素取 alpha 最大值合成。
 *
 * xAdvance 始终取填充字形的步进（描边不改变步进）；xOffset/yOffset 以并集包围盒为准。
 * strokeWidthPx <= 0、stroker 不可用或描边失败时退化为纯填充结果（填充总是合法输出）。
 *
 * 线程前提：FT_Stroker 非线程安全，调用方（Java 层）必须按 face 句柄串行调用。
 */
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

    // 先取轮廓副本：FT_Render_Glyph 会把 slot 转为位图格式，
    // 之后 FT_Get_Glyph 只能拿到位图而非轮廓，因此必须在渲染前提取。
    // 轮廓副本独立于 slot，后续渲染 slot 不影响它。
    // 内嵌位图字形没有轮廓（format 非 OUTLINE），此时退化纯填充。
    FT_Glyph outline = nullptr;
    if (FT_Get_Glyph(slot, &outline) != 0 || outline == nullptr
            || outline->format != FT_GLYPH_FORMAT_OUTLINE) {
        if (outline != nullptr) {
            FT_Done_Glyph(outline);
        }
        outline = nullptr;
    }

    // 渲染填充字形（描边不改变 advance/bearing 基准，全部以填充字形为准）
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
    // inside=false：只保留外扩外环；destroy=false：失败时轮廓仍可安全释放
    if (FT_Glyph_StrokeBorder(&outline, handle->stroker, false, false) != 0) {
        FT_Done_Glyph(outline);
        return grayBitmapToJava(env, fill, baseline, xAdvance);
    }
    // destroy=false：转换产出新位图字形写入 strokeBitmap，原轮廓句柄保持有效，
    // 显式 FT_Done_Glyph 释放——destroy=true 的失败路径是否释放轮廓在不同版本语义含混，不用。
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

    // 包围盒并集（y 轴向上：top 为上边缘，bottom = top - height）
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
    // 注意不能用 merged.empty() 判断：此刻 alpha 尚未分配，empty() 恒为真
    if (merged.width > 0 && merged.height > 0) {
        merged.alpha.assign(
                static_cast<size_t>(merged.width) * static_cast<size_t>(merged.height), 0);
    }

    // 逐像素取两张位图的 alpha 最大值（同一坐标系：left/top bearing，y 轴向上）
    const auto blendInto = [&merged](const GrayBitmap& src) {
        if (src.empty()) {
            return;
        }
        for (int sy = 0; sy < src.height; sy++) {
            // 源像素行的上边缘 y 坐标（向上为正）：src.top - sy
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
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.font.NativeFontRasterizer#nativeRasterizeGlyphStroked(long, int, int, float)
 *
 * @param env JNI 环境
 * @param clazz Java 类对象（未使用）
 * @param faceHandle Java 层持有的字体句柄
 * @param codePoint 要栅格化的 Unicode code point
 * @param baseline 基线位置
 * @param strokeWidthPx 描边宽度（像素）；<= 0 时退化为纯填充
 * @return NativeGlyphBitmap；失败时返回 null
 *
 * 内存管理：返回对象由 JVM 管理；不转移底层 FreeType 句柄所有权。
 * 线程前提：FT_Stroker 非线程安全，调用方必须按 face 句柄串行调用。
 */
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

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.font.NativeFontRasterizer#nativeRasterizeGlyphs(long, int[], int, float)
 *
 * 批量栅格化：逐码点调用填充（strokeWidthPx <= 0）或描边剪影（strokeWidthPx > 0）路径，
 * 摊薄 JNI 边界开销。单码点失败（如 FT_Load_Char 失败）对应元素为 null，不中断其余。
 *
 * @param env JNI 环境
 * @param clazz Java 类对象（未使用）
 * @param faceHandle Java 层持有的字体句柄
 * @param codePoints 待栅格化的 Unicode code point 数组
 * @param baseline 基线位置
 * @param strokeWidthPx 描边宽度（像素）；<= 0 时为纯填充
 * @return NativeGlyphBitmap[]，长度与 codePoints 一致；入参非法或数组分配失败返回 null
 *
 * 内存管理：返回数组及元素由 JVM 管理；逐元素 DeleteLocalRef 防止大批量时撑爆局部引用表。
 * 线程前提：FT_Stroker 非线程安全，调用方必须按 face 句柄串行调用。
 */
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
            // 单码点失败：元素保持 null，继续其余码点
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

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.font.NativeFontRasterizer#nativeHasGlyph(long, int)
 *
 * 字形存在性查询：FT_Get_Char_Index(face, cp) != 0。码点 <= 0 恒 false（.notdef
 * 占位查询无意义）。face 链回退选择用，不改变任何栅格化语义。
 *
 * @param env JNI 环境
 * @param clazz Java 类对象（未使用）
 * @param faceHandle Java 层持有的字体句柄
 * @param codePoint 待查询的 Unicode code point
 * @return face 含该字形返回 JNI_TRUE
 *
 * 内存管理：纯查询，无资源分配/转移。
 */
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
