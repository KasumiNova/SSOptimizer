/**
 * Windows IMM32 输入法原生实现（JNI 实现）。
 *
 * 对应 Java 类: WindowsImmNative
 * 负责绑定 LWJGL 2 的 HWND，接管 WM_IME_* 消息，维护候选窗位置，
 * 并向 Java 层暴露提交文本与组合串查询接口。
 */
#include "github_kasuminova_ssoptimizer_common_input_ime_WindowsImmNative.h"

#include <jni.h>

#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <imm.h>
#define SSOPTIMIZER_NATIVE_WINDOWS_IME_AVAILABLE 1
#else
#define SSOPTIMIZER_NATIVE_WINDOWS_IME_AVAILABLE 0
#endif

namespace {

constexpr const char* WINDOWS_IME_UNAVAILABLE = "Windows IMM32 backend is unavailable on non-Windows hosts";

#if SSOPTIMIZER_NATIVE_WINDOWS_IME_AVAILABLE
thread_local std::string g_last_error;
constexpr wchar_t WINDOWS_IME_CONTEXT_PROPERTY[] = L"SSOptimizer.WindowsImmContext";

struct WindowsImmContext {
    HWND                    hwnd = nullptr;
    WNDPROC                 originalWndProc = nullptr;
    bool                    focused = false;
    bool                    composing = false;
    int                     spotX = 0;
    int                     spotY = 0;
    int                     spotHeight = 0;
    std::deque<std::string> committedTexts;
    std::string             preeditText;
    std::string             lastError;
    mutable std::mutex      mutex;
};

void setLastError(const std::string& value) {
    g_last_error = value;
}

void clearLastError() {
    g_last_error.clear();
}

void setContextError(WindowsImmContext* context,
                     const std::string& value) {
    if (context != nullptr) {
        std::lock_guard<std::mutex> lock(context->mutex);
        context->lastError = value;
    }
    setLastError(value);
}

void clearContextError(WindowsImmContext* context) {
    if (context != nullptr) {
        std::lock_guard<std::mutex> lock(context->mutex);
        context->lastError.clear();
    }
    clearLastError();
}

std::string utf8FromWide(const std::wstring& text) {
    if (text.empty()) {
        return "";
    }

    const int required = WideCharToMultiByte(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), nullptr, 0, nullptr, nullptr);
    if (required <= 0) {
        return "";
    }

    std::string result(static_cast<size_t>(required), '\0');
    WideCharToMultiByte(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), result.data(), required, nullptr, nullptr);
    return result;
}

std::string readCompositionStringUtf8(HWND hwnd,
                                      const DWORD index,
                                      WindowsImmContext* context) {
    HIMC himc = ImmGetContext(hwnd);
    if (himc == nullptr) {
        setContextError(context, "ImmGetContext returned null");
        return "";
    }

    const LONG byteCount = ImmGetCompositionStringW(himc, index, nullptr, 0);
    if (byteCount == IMM_ERROR_NODATA || byteCount == 0) {
        ImmReleaseContext(hwnd, himc);
        return "";
    }
    if (byteCount < 0) {
        ImmReleaseContext(hwnd, himc);
        setContextError(context, "ImmGetCompositionStringW failed");
        return "";
    }

    std::vector<wchar_t> buffer(static_cast<size_t>(byteCount / sizeof(wchar_t)) + 1, L'\0');
    const LONG copied = ImmGetCompositionStringW(himc, index, buffer.data(), byteCount);
    ImmReleaseContext(hwnd, himc);
    if (copied < 0) {
        setContextError(context, "ImmGetCompositionStringW failed");
        return "";
    }

    return utf8FromWide(std::wstring(buffer.data(), static_cast<size_t>(copied / sizeof(wchar_t))));
}

void setImmOpenStatus(WindowsImmContext* context,
                      const bool open) {
    if (context == nullptr || context->hwnd == nullptr || !IsWindow(context->hwnd)) {
        return;
    }

    HIMC himc = ImmGetContext(context->hwnd);
    if (himc == nullptr) {
        setContextError(context, "ImmGetContext returned null");
        return;
    }

    ImmSetOpenStatus(himc, open ? TRUE : FALSE);
    ImmReleaseContext(context->hwnd, himc);
}

void updateImmSpot(WindowsImmContext* context) {
    if (context == nullptr || context->hwnd == nullptr || !IsWindow(context->hwnd)) {
        return;
    }

    HIMC himc = ImmGetContext(context->hwnd);
    if (himc == nullptr) {
        setContextError(context, "ImmGetContext returned null");
        return;
    }

    int spotX = 0;
    int spotY = 0;
    int spotHeight = 0;
    {
        std::lock_guard<std::mutex> lock(context->mutex);
        spotX = context->spotX;
        spotY = context->spotY;
        spotHeight = context->spotHeight;
    }

    COMPOSITIONFORM composition = {};
    composition.dwStyle = CFS_POINT;
    composition.ptCurrentPos.x = spotX;
    composition.ptCurrentPos.y = spotY;
    ImmSetCompositionWindow(himc, &composition);

    CANDIDATEFORM candidate = {};
    candidate.dwIndex = 0;
    candidate.dwStyle = CFS_CANDIDATEPOS;
    candidate.ptCurrentPos.x = spotX;
    candidate.ptCurrentPos.y = spotY + spotHeight;
    ImmSetCandidateWindow(himc, &candidate);

    ImmReleaseContext(context->hwnd, himc);
    clearContextError(context);
}

void handleImmComposition(WindowsImmContext* context,
                          const LPARAM lParam) {
    if (context == nullptr || context->hwnd == nullptr) {
        return;
    }

    std::string resultText;
    std::string preeditText;
    const bool hasResult = (lParam & GCS_RESULTSTR) != 0;
    const bool hasPreedit = (lParam & GCS_COMPSTR) != 0;

    if (hasResult) {
        resultText = readCompositionStringUtf8(context->hwnd, GCS_RESULTSTR, context);
    }
    if (hasPreedit) {
        preeditText = readCompositionStringUtf8(context->hwnd, GCS_COMPSTR, context);
    }

    std::lock_guard<std::mutex> lock(context->mutex);
    if (!resultText.empty()) {
        context->committedTexts.push_back(resultText);
    }
    if (hasPreedit) {
        context->preeditText = preeditText;
        context->composing = !preeditText.empty();
    } else if (hasResult) {
        context->preeditText.clear();
        context->composing = false;
    }
    context->lastError.clear();
    clearLastError();
}

LRESULT CALLBACK imeWindowProc(HWND hwnd,
                               UINT message,
                               WPARAM wParam,
                               LPARAM lParam) {
    auto* context = reinterpret_cast<WindowsImmContext*>(GetPropW(hwnd, WINDOWS_IME_CONTEXT_PROPERTY));
    WNDPROC originalWndProc = context != nullptr ? context->originalWndProc : nullptr;

    switch (message) {
        case WM_IME_STARTCOMPOSITION:
            if (context != nullptr) {
                std::lock_guard<std::mutex> lock(context->mutex);
                context->composing = true;
                context->preeditText.clear();
            }
            break;
        case WM_IME_COMPOSITION:
            handleImmComposition(context, lParam);
            break;
        case WM_IME_ENDCOMPOSITION:
            if (context != nullptr) {
                std::lock_guard<std::mutex> lock(context->mutex);
                context->composing = false;
                context->preeditText.clear();
            }
            break;
        default:
            break;
    }

    if (originalWndProc != nullptr) {
        return CallWindowProcW(originalWndProc, hwnd, message, wParam, lParam);
    }
    return DefWindowProcW(hwnd, message, wParam, lParam);
}

bool installSubclass(WindowsImmContext* context) {
    if (context == nullptr || context->hwnd == nullptr) {
        return false;
    }
    if (GetPropW(context->hwnd, WINDOWS_IME_CONTEXT_PROPERTY) != nullptr) {
        setContextError(context, "Windows IMM context already attached");
        return false;
    }
    if (!SetPropW(context->hwnd, WINDOWS_IME_CONTEXT_PROPERTY, context)) {
        setContextError(context, "SetPropW failed");
        return false;
    }

    ::SetLastError(0);
    const LONG_PTR previous = SetWindowLongPtrW(context->hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(&imeWindowProc));
    const DWORD win32Error = ::GetLastError();
    if (previous == 0 && win32Error != 0) {
        RemovePropW(context->hwnd, WINDOWS_IME_CONTEXT_PROPERTY);
        setContextError(context, "SetWindowLongPtrW failed");
        return false;
    }

    context->originalWndProc = reinterpret_cast<WNDPROC>(previous);
    return true;
}

void removeSubclass(WindowsImmContext* context) {
    if (context == nullptr || context->hwnd == nullptr || !IsWindow(context->hwnd)) {
        return;
    }

    if (GetPropW(context->hwnd, WINDOWS_IME_CONTEXT_PROPERTY) == context) {
        RemovePropW(context->hwnd, WINDOWS_IME_CONTEXT_PROPERTY);
    }
    if (context->originalWndProc != nullptr) {
        SetWindowLongPtrW(context->hwnd, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(context->originalWndProc));
    }
}

std::string describeContext(const WindowsImmContext* context) {
    if (context == nullptr) {
        return "imm32:null-context";
    }
    std::lock_guard<std::mutex> lock(context->mutex);
    return "hwnd=0x" + std::to_string(static_cast<unsigned long long>(reinterpret_cast<std::uintptr_t>(context->hwnd)))
            + " focused=" + (context->focused ? "true" : "false")
            + " composing=" + (context->composing ? "true" : "false")
            + " pendingCommits=" + std::to_string(context->committedTexts.size())
            + " spot=" + std::to_string(context->spotX) + "," + std::to_string(context->spotY)
            + "," + std::to_string(context->spotHeight);
}
#endif

jstring newString(JNIEnv* env,
                  const char* value) {
    return env->NewStringUTF(value != nullptr ? value : "");
}

} // namespace

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.input.ime.WindowsImmNative#nativeIsSupported()
 *
 * @param env JNI 环境
 * @param clazz Java 类对象（未使用）
 * @return Windows 主机返回 true，其他主机返回 false
 *
 * 内存管理：不分配需要调用方释放的原生资源。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_WindowsImmNative_nativeIsSupported(JNIEnv* env,
                                                                                       jclass clazz) {
    (void) env;
    (void) clazz;
#if SSOPTIMIZER_NATIVE_WINDOWS_IME_AVAILABLE
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.input.ime.WindowsImmNative#nativeLastErrorMessage()
 *
 * @param env JNI 环境
 * @param clazz Java 类对象（未使用）
 * @return 最近一次原生错误文本；非 Windows 主机返回不可用说明
 *
 * 内存管理：返回的 jstring 由 JVM 管理。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_WindowsImmNative_nativeLastErrorMessage(JNIEnv* env,
                                                                                             jclass clazz) {
    (void) clazz;
#if SSOPTIMIZER_NATIVE_WINDOWS_IME_AVAILABLE
    return newString(env, g_last_error.empty() ? "" : g_last_error.c_str());
#else
    return newString(env, WINDOWS_IME_UNAVAILABLE);
#endif
}

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.input.ime.WindowsImmNative#nativeDebugSummary(long)
 *
 * @param env JNI 环境
 * @param clazz Java 类对象（未使用）
 * @param handle 上下文句柄
 * @return 调试摘要文本
 *
 * 内存管理：返回的 jstring 由 JVM 管理。
 */
extern "C" JNIEXPORT jstring JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_WindowsImmNative_nativeDebugSummary(JNIEnv* env,
                                                                                         jclass clazz,
                                                                                         jlong handle) {
    (void) clazz;
#if SSOPTIMIZER_NATIVE_WINDOWS_IME_AVAILABLE
    const auto* context = reinterpret_cast<WindowsImmContext*>(static_cast<std::uintptr_t>(handle));
    const std::string summary = describeContext(context);
    return newString(env, summary.c_str());
#else
    (void) handle;
    return newString(env, "imm32:unavailable");
#endif
}

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.input.ime.WindowsImmNative#nativeCreateContext(long, long)
 *
 * @param env JNI 环境
 * @param clazz Java 类对象（未使用）
 * @param display 显示句柄（保留参数，当前未使用）
 * @param window LWJGL 2 暴露的 HWND
 * @return 成功时返回原生上下文句柄，失败返回 0
 *
 * 内存管理：不分配原生资源。
 */
extern "C" JNIEXPORT jlong JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_WindowsImmNative_nativeCreateContext(JNIEnv* env,
                                                                                          jclass clazz,
                                                                                          jlong display,
                                                                                          jlong window) {
    (void) env;
    (void) clazz;
    (void) display;
#if SSOPTIMIZER_NATIVE_WINDOWS_IME_AVAILABLE
    const auto hwnd = reinterpret_cast<HWND>(static_cast<std::uintptr_t>(window));
    clearLastError();
    if (hwnd == nullptr || !IsWindow(hwnd)) {
        setLastError("Invalid HWND");
        return 0;
    }
    auto* context = new WindowsImmContext();
    context->hwnd = hwnd;
    if (!installSubclass(context)) {
        delete context;
        return 0;
    }
    clearContextError(context);
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(context));
#else
    (void) window;
    return 0;
#endif
}

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.input.ime.WindowsImmNative#nativeDestroyContext(long)
 *
 * @param env JNI 环境
 * @param clazz Java 类对象（未使用）
 * @param handle 上下文句柄（未使用）
 *
 * 内存管理：恢复窗口原始 WndProc，并释放 nativeCreateContext 分配的上下文对象。
 */
extern "C" JNIEXPORT void JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_WindowsImmNative_nativeDestroyContext(JNIEnv* env,
                                                                                           jclass clazz,
                                                                                           jlong handle) {
    (void) env;
    (void) clazz;
#if SSOPTIMIZER_NATIVE_WINDOWS_IME_AVAILABLE
    auto* context = reinterpret_cast<WindowsImmContext*>(static_cast<std::uintptr_t>(handle));
    if (context != nullptr) {
        removeSubclass(context);
    }
    delete context;
#else
    (void) handle;
#endif
}

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.input.ime.WindowsImmNative#nativeFocusIn(long)
 */
extern "C" JNIEXPORT void JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_WindowsImmNative_nativeFocusIn(JNIEnv* env,
                                                                                    jclass clazz,
                                                                                    jlong handle) {
    (void) env;
    (void) clazz;
#if SSOPTIMIZER_NATIVE_WINDOWS_IME_AVAILABLE
    auto* context = reinterpret_cast<WindowsImmContext*>(static_cast<std::uintptr_t>(handle));
    if (context != nullptr) {
        {
            std::lock_guard<std::mutex> lock(context->mutex);
            context->focused = true;
        }
        setImmOpenStatus(context, true);
        updateImmSpot(context);
    }
#else
    (void) handle;
#endif
}

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.input.ime.WindowsImmNative#nativeFocusOut(long)
 */
extern "C" JNIEXPORT void JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_WindowsImmNative_nativeFocusOut(JNIEnv* env,
                                                                                     jclass clazz,
                                                                                     jlong handle) {
    (void) env;
    (void) clazz;
#if SSOPTIMIZER_NATIVE_WINDOWS_IME_AVAILABLE
    auto* context = reinterpret_cast<WindowsImmContext*>(static_cast<std::uintptr_t>(handle));
    if (context != nullptr) {
        {
            std::lock_guard<std::mutex> lock(context->mutex);
            context->focused = false;
            context->composing = false;
            context->preeditText.clear();
            context->lastError.clear();
        }
        setImmOpenStatus(context, false);
        clearLastError();
    }
#else
    (void) handle;
#endif
}

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.input.ime.WindowsImmNative#nativeUpdateSpot(long, int, int, int)
 */
extern "C" JNIEXPORT void JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_WindowsImmNative_nativeUpdateSpot(JNIEnv* env,
                                                                                       jclass clazz,
                                                                                       jlong handle,
                                                                                       jint x,
                                                                                       jint y,
                                                                                       jint height) {
    (void) env;
    (void) clazz;
#if SSOPTIMIZER_NATIVE_WINDOWS_IME_AVAILABLE
    auto* context = reinterpret_cast<WindowsImmContext*>(static_cast<std::uintptr_t>(handle));
    if (context != nullptr) {
        context->spotX = x;
        context->spotY = y;
        context->spotHeight = height;
        if (context->focused) {
            updateImmSpot(context);
        }
    }
#else
    (void) handle;
    (void) x;
    (void) y;
    (void) height;
#endif
}

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.input.ime.WindowsImmNative#nativePollCommittedText(long)
 *
 * @return 下一段待消费的提交文本；没有提交文本时返回 null
 */
extern "C" JNIEXPORT jstring JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_WindowsImmNative_nativePollCommittedText(JNIEnv* env,
                                                                                              jclass clazz,
                                                                                              jlong handle) {
    (void) clazz;
#if SSOPTIMIZER_NATIVE_WINDOWS_IME_AVAILABLE
    auto* context = reinterpret_cast<WindowsImmContext*>(static_cast<std::uintptr_t>(handle));
    if (context == nullptr) {
        return nullptr;
    }

    std::string value;
    {
        std::lock_guard<std::mutex> lock(context->mutex);
        if (context->committedTexts.empty()) {
            return nullptr;
        }
        value = context->committedTexts.front();
        context->committedTexts.pop_front();
    }
    return newString(env, value.c_str());
#else
    (void) env;
    (void) handle;
    return nullptr;
#endif
}

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.input.ime.WindowsImmNative#nativeCurrentPreeditText(long)
 *
 * @return 当前组合串；没有组合串时返回空字符串
 */
extern "C" JNIEXPORT jstring JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_WindowsImmNative_nativeCurrentPreeditText(JNIEnv* env,
                                                                                               jclass clazz,
                                                                                               jlong handle) {
    (void) clazz;
#if SSOPTIMIZER_NATIVE_WINDOWS_IME_AVAILABLE
    auto* context = reinterpret_cast<WindowsImmContext*>(static_cast<std::uintptr_t>(handle));
    if (context == nullptr) {
        return newString(env, "");
    }

    std::string value;
    {
        std::lock_guard<std::mutex> lock(context->mutex);
        value = context->preeditText;
    }
    return newString(env, value.c_str());
#else
    (void) handle;
    return newString(env, "");
#endif
}

/**
 * 对应 Java 方法：github.kasuminova.ssoptimizer.common.input.ime.WindowsImmNative#nativeIsComposing(long)
 *
 * @return 当前是否处于输入法组合态
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_WindowsImmNative_nativeIsComposing(JNIEnv* env,
                                                                                        jclass clazz,
                                                                                        jlong handle) {
    (void) env;
    (void) clazz;
#if SSOPTIMIZER_NATIVE_WINDOWS_IME_AVAILABLE
    auto* context = reinterpret_cast<WindowsImmContext*>(static_cast<std::uintptr_t>(handle));
    if (context == nullptr) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(context->mutex);
    return context->composing ? JNI_TRUE : JNI_FALSE;
#else
    (void) handle;
    return JNI_FALSE;
#endif
}