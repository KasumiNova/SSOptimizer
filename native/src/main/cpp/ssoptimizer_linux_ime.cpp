/**
 * Linux XIM 输入法原生实现（JNI 实现）。
 *
 * 对应 Java 类: LinuxXimNative
 * 通过 X11 Input Method (XIM) 协议实现中文输入，
 * 包含 XIC 创建/销毁、XFilterEvent 调度、Xutf8LookupString 查找、
 * 光标位置更新等完整的 XIM 客户端实现。
 *
 * 内存管理策略：
 * - ImeContext 通过 createContext 分配，destroyContext 释放
 * - XIM/XIC 生命周期绑定在 ImeContext 上
 * - FontSet 随 XIC 一起释放
 */
#include "github_kasuminova_ssoptimizer_common_input_ime_LinuxXimNative.h"

#include <jni.h>

#include <clocale>
#include <cstdlib>
#include <cstdint>
#include <sstream>
#include <string>
#include <vector>

#if defined(SSOPTIMIZER_HAVE_X11) && __has_include(<X11/Xlib.h>) && __has_include(<X11/Xutil.h>)
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#define SSOPTIMIZER_NATIVE_X11_AVAILABLE 1
#else
#define SSOPTIMIZER_NATIVE_X11_AVAILABLE 0
#endif

namespace {

#if SSOPTIMIZER_NATIVE_X11_AVAILABLE
thread_local std::string g_last_error;

struct LinuxXimContext {
    Display*    display = nullptr;
    Window      window = 0;
    XIM         xim = nullptr;
    XIC         xic = nullptr;
    XIMStyle    inputStyle = 0;
    long        originalEventMask = 0;
    long        filterEventMask = 0;
    std::string requestedLocaleModifiers;
    std::string appliedLocaleModifiers;
    std::string committedText;
    std::string preeditText;
    std::string lastKeyEventSummary;
    bool        composing = false;
};

std::string g_requested_locale_modifiers;
std::string g_applied_locale_modifiers;

void setLastError(const std::string& value) {
    g_last_error = value;
}

void clearLastError() {
    g_last_error.clear();
}

std::string envValue(const char* name) {
    const char* value = std::getenv(name);
    return value != nullptr ? std::string(value) : std::string("<unset>");
}

void ensureLocaleConfigured() {
    static bool configured = false;
    if (configured) {
        return;
    }

    std::setlocale(LC_CTYPE, "");
    const char* requestedModifiers = std::getenv("XMODIFIERS");
    g_requested_locale_modifiers = requestedModifiers != nullptr && requestedModifiers[0] != '\0'
            ? std::string(requestedModifiers)
            : std::string("<unset>");

    const char* appliedModifiers = XSetLocaleModifiers("");
    g_applied_locale_modifiers = appliedModifiers != nullptr
            ? std::string(appliedModifiers)
            : std::string("<null>");
    configured = true;
}

bool containsStyle(XIMStyles* styles,
                   const XIMStyle style) {
    if (styles == nullptr) {
        return false;
    }
    for (unsigned short i = 0; i < styles->count_styles; i++) {
        if (styles->supported_styles[i] == style) {
            return true;
        }
    }
    return false;
}

void appendCandidateStyle(std::vector<XIMStyle>& candidates,
                          const XIMStyle style) {
    for (const XIMStyle candidate : candidates) {
        if (candidate == style) {
            return;
        }
    }
    candidates.push_back(style);
}

std::vector<XIMStyle> chooseInputStyles(XIM xim,
                                        const int requestedStyle) {
    if (xim == nullptr) {
        return {};
    }

    XIMStyles* styles = nullptr;
    const char* queryError = XGetIMValues(xim, XNQueryInputStyle, &styles, nullptr);
    if (queryError != nullptr || styles == nullptr) {
        setLastError(queryError != nullptr
                ? std::string("XGetIMValues(XNQueryInputStyle) failed at ") + queryError
                : std::string("XGetIMValues(XNQueryInputStyle) returned null styles"));
        return {};
    }

    const XIMStyle preferred = requestedStyle != 0
            ? static_cast<XIMStyle>(requestedStyle)
            : static_cast<XIMStyle>(XIMPreeditPosition | XIMStatusNothing);
    const XIMStyle fallbackPreeditNothing = static_cast<XIMStyle>(XIMPreeditNothing | XIMStatusNothing);
    const XIMStyle fallbackPreeditNone = static_cast<XIMStyle>(XIMPreeditNone | XIMStatusNothing);
    const XIMStyle fallbackStatusNone = static_cast<XIMStyle>(XIMPreeditNothing | XIMStatusNone);

    std::vector<XIMStyle> candidates;
    if (containsStyle(styles, preferred)) {
        appendCandidateStyle(candidates, preferred);
    }
    if (containsStyle(styles, fallbackPreeditNothing)) {
        appendCandidateStyle(candidates, fallbackPreeditNothing);
    }
    if (containsStyle(styles, fallbackPreeditNone)) {
        appendCandidateStyle(candidates, fallbackPreeditNone);
    }
    if (containsStyle(styles, fallbackStatusNone)) {
        appendCandidateStyle(candidates, fallbackStatusNone);
    }
    for (unsigned short i = 0; i < styles->count_styles; i++) {
        appendCandidateStyle(candidates, styles->supported_styles[i]);
    }

    XFree(styles);
    if (candidates.empty()) {
        setLastError("No supported XIM input style matched preferred/fallback request");
    }
    return candidates;
}

std::string describeStyle(const XIMStyle style) {
    std::ostringstream out;
    out << "0x" << std::hex << static_cast<unsigned long>(style);
    return out.str();
}

std::string describeMask(const long mask) {
    std::ostringstream out;
    out << "0x" << std::hex << static_cast<unsigned long>(mask);
    return out.str();
}

XFontSet createMinimalFontSet(Display* display) {
    char** missingCharsets = nullptr;
    int missingCount = 0;
    char* defaultString = nullptr;

    // Try common font patterns that are almost always available
    const char* fontPatterns[] = {
        "-*-*-*-*-*-*-*-*-*-*-*-*-*-*",
        "fixed",
        "*",
        nullptr
    };

    for (int i = 0; fontPatterns[i] != nullptr; i++) {
        XFontSet fontSet = XCreateFontSet(display, fontPatterns[i],
                &missingCharsets, &missingCount, &defaultString);
        if (missingCharsets != nullptr) {
            XFreeStringList(missingCharsets);
            missingCharsets = nullptr;
        }
        if (fontSet != nullptr) {
            return fontSet;
        }
    }
    return nullptr;
}

XIC createIcForStyle(XIM xim,
                     Display* display,
                     const Window window,
                     const XIMStyle inputStyle) {
    XIC xic = nullptr;
    if ((inputStyle & XIMPreeditPosition) != 0) {
        XFontSet fontSet = createMinimalFontSet(display);
        if (fontSet != nullptr) {
            XPoint spot{0, 0};
            XVaNestedList preeditAttributes = XVaCreateNestedList(0,
                    XNSpotLocation, &spot,
                    XNFontSet, fontSet,
                    nullptr);
            if (preeditAttributes != nullptr) {
                xic = XCreateIC(
                        xim,
                        XNInputStyle, inputStyle,
                        XNClientWindow, window,
                        XNFocusWindow, window,
                        XNPreeditAttributes, preeditAttributes,
                        nullptr
                );
                XFree(preeditAttributes);
            } else {
                setLastError("XVaCreateNestedList returned null for XNSpotLocation+XNFontSet");
            }
            // FontSet is owned by the XIC now for PreeditPosition; don't free it here.
            // If XCreateIC failed, free the fontSet.
            if (xic == nullptr) {
                XFreeFontSet(display, fontSet);
            }
        } else {
            setLastError("XCreateFontSet returned null for all font patterns");
        }
    }
    if (xic == nullptr) {
        xic = XCreateIC(
                xim,
                XNInputStyle, inputStyle,
                XNClientWindow, window,
                XNFocusWindow, window,
                nullptr
        );
    }
    return xic;
}

bool installIcFilterEvents(LinuxXimContext* context) {
    if (context == nullptr || context->display == nullptr || context->window == 0 || context->xic == nullptr) {
        return false;
    }

    long filterEventMask = 0;
    const char* filterError = XGetICValues(context->xic, XNFilterEvents, &filterEventMask, nullptr);
    if (filterError != nullptr) {
        setLastError(std::string("XGetICValues(XNFilterEvents) failed at ") + filterError);
        return false;
    }

    XWindowAttributes windowAttributes{};
    if (XGetWindowAttributes(context->display, context->window, &windowAttributes) == 0) {
        setLastError("XGetWindowAttributes failed while installing XIC filter events");
        return false;
    }

    context->originalEventMask = windowAttributes.your_event_mask;
    context->filterEventMask = filterEventMask;

    const long combinedMask = windowAttributes.your_event_mask | filterEventMask;
    if (combinedMask != windowAttributes.your_event_mask) {
        XSelectInput(context->display, context->window, combinedMask);
        XFlush(context->display);
    }

    return true;
}

void destroyContext(LinuxXimContext* context) {
    if (context == nullptr) {
        return;
    }

    if (context->display != nullptr && context->window != 0 && context->filterEventMask != 0) {
        XSelectInput(context->display, context->window, context->originalEventMask);
    }

    if (context->xic != nullptr) {
        XDestroyIC(context->xic);
        context->xic = nullptr;
    }
    if (context->xim != nullptr) {
        XCloseIM(context->xim);
        context->xim = nullptr;
    }
    delete context;
}

LinuxXimContext* createContext(const jlong displayHandle,
                               const jlong windowHandle,
                               const jint requestedStyle) {
    if (displayHandle == 0L || windowHandle == 0L) {
        setLastError("Display or window handle was zero");
        return nullptr;
    }

    ensureLocaleConfigured();
    if (!XSupportsLocale()) {
        setLastError("XSupportsLocale() returned false");
        return nullptr;
    }

    Display* display = reinterpret_cast<Display*>(static_cast<std::uintptr_t>(displayHandle));
    const Window window = static_cast<Window>(windowHandle);

    XIM xim = XOpenIM(display, nullptr, nullptr, nullptr);
    if (xim == nullptr) {
        setLastError(std::string("XOpenIM returned null; XMODIFIERS=") + envValue("XMODIFIERS")
                + " LANG=" + envValue("LANG")
                + " LC_CTYPE=" + envValue("LC_CTYPE"));
        return nullptr;
    }

    const std::vector<XIMStyle> candidateStyles = chooseInputStyles(xim, requestedStyle);
    if (candidateStyles.empty()) {
        if (g_last_error.empty()) {
            setLastError("chooseInputStyles returned no candidates");
        }
        XCloseIM(xim);
        return nullptr;
    }

    XIC xic = nullptr;
    XIMStyle inputStyle = 0;
    std::vector<std::string> attemptedStyles;
    for (const XIMStyle candidateStyle : candidateStyles) {
        attemptedStyles.push_back(describeStyle(candidateStyle));
        xic = createIcForStyle(xim, display, window, candidateStyle);
        if (xic != nullptr) {
            inputStyle = candidateStyle;
            break;
        }
    }
    if (xic == nullptr) {
        std::ostringstream out;
        out << "XCreateIC returned null for all candidate styles [";
        for (size_t i = 0; i < attemptedStyles.size(); i++) {
            if (i > 0) {
                out << ", ";
            }
            out << attemptedStyles[i];
        }
        out << "]";
        if (!g_last_error.empty()) {
            out << "; lastError=" << g_last_error;
        }
        setLastError(out.str());
        XCloseIM(xim);
        return nullptr;
    }

    auto* context = new LinuxXimContext();
    context->display = display;
    context->window = window;
    context->xim = xim;
    context->xic = xic;
    context->inputStyle = inputStyle;
    context->requestedLocaleModifiers = g_requested_locale_modifiers;
    context->appliedLocaleModifiers = g_applied_locale_modifiers;
    if (!installIcFilterEvents(context)) {
        destroyContext(context);
        return nullptr;
    }
    clearLastError();
    return context;
}

void updateSpot(LinuxXimContext* context,
                const jint x,
                const jint y,
                const jint height) {
    if (context == nullptr || context->xic == nullptr) {
        return;
    }

    XPoint spot{
            static_cast<short>(x),
            static_cast<short>(y + height)
    };
    XVaNestedList preeditAttributes = XVaCreateNestedList(0, XNSpotLocation, &spot, nullptr);
    if (preeditAttributes == nullptr) {
        return;
    }

    XSetICValues(context->xic, XNPreeditAttributes, preeditAttributes, nullptr);
    XFree(preeditAttributes);
    if (context->display != nullptr) {
        XFlush(context->display);
    }
}

bool containsControlCharacters(const char* value,
                              const int length) {
    if (value == nullptr || length <= 0) {
        return false;
    }

    for (int i = 0; i < length; i++) {
        const unsigned char ch = static_cast<unsigned char>(value[i]);
        if ((ch < 0x20u || ch == 0x7fu) && ch != '\n' && ch != '\r' && ch != '\t') {
            return true;
        }
    }

    return false;
}

bool handleKeyEvent(LinuxXimContext* context,
                    XEvent* event,
                    const jint eventType) {
    (void) eventType;
    if (context == nullptr || context->xic == nullptr || event == nullptr) {
        return false;
    }

    // SSOptimizer is the sole caller of XFilterEvent — the LWJGL native
    // nFilterEvent has been replaced to always return false, so the IM
    // server only sees each event once through our XIC.
    //
    // The XIM protocol requires ALL X events (not just key events) to pass
    // through XFilterEvent.  The IM uses FocusIn/Out, ClientMessage, and
    // other event types to manage its internal state and deliver commits.
    if (XFilterEvent(event, None)) {
        // The input method consumed this event.
        if (event->type == KeyPress || event->type == KeyRelease) {
            context->composing = true;
            std::ostringstream oss;
            oss << "filtered-by-xim (composing) type=" << event->type
                << " keycode=" << event->xkey.keycode
                << " state=0x" << std::hex << event->xkey.state;
            context->lastKeyEventSummary = oss.str();
        }
        return true;
    }

    // For non-KeyPress events, we only needed the XFilterEvent call above.
    if (event->type != KeyPress) {
        return false;
    }

    // The IM did not consume this KeyPress — look up committed text.
    context->committedText.clear();
    context->composing = false;

    // Check if this is a synthetic event (sent by the IM via XSendEvent)
    const bool isSynthetic = event->xkey.send_event != 0;

    KeySym keySym = 0;
    Status status = 0;
    char inlineBuffer[256] = {0};
    const unsigned int keycode = event->xkey.keycode;
    const unsigned int rawState = event->xkey.state;
    XKeyEvent lookupEvent = event->xkey;
    lookupEvent.state &= static_cast<unsigned int>(~(Mod2Mask | Mod5Mask));
    const unsigned int normalizedState = lookupEvent.state;
    int length = Xutf8LookupString(context->xic, &lookupEvent, inlineBuffer, sizeof(inlineBuffer) - 1, &keySym, &status);

    auto buildSummary = [&](const int committedLength, const char* committedText) {
        std::ostringstream out;
        out << "synthetic=" << (isSynthetic ? "true" : "false")
            << " keycode=" << keycode
            << " state=0x" << std::hex << rawState
            << " normalizedState=0x" << std::hex << normalizedState
            << " keysym=0x" << std::hex << static_cast<unsigned long>(keySym)
            << " status=" << std::dec << status
            << " length=" << committedLength
            << " committed=";
        if (committedText != nullptr && committedLength > 0) {
            out << '"';
            for (int i = 0; i < committedLength; i++) {
                const char ch = committedText[i];
                if (ch == '\\') {
                    out << "\\\\";
                } else if (ch == '"') {
                    out << "\\\"";
                } else if (ch == '\n') {
                    out << "\\n";
                } else if (ch == '\r') {
                    out << "\\r";
                } else if (ch == '\t') {
                    out << "\\t";
                } else {
                    out << ch;
                }
            }
            out << '"';
        }
        return out.str();
    };

    if (status == XBufferOverflow && length > 0) {
        std::vector<char> dynamicBuffer(static_cast<size_t>(length) + 1, '\0');
        length = Xutf8LookupString(context->xic, &lookupEvent, dynamicBuffer.data(), length, &keySym, &status);
        if ((status == XLookupChars || status == XLookupBoth) && length > 0
                && !containsControlCharacters(dynamicBuffer.data(), length)) {
            context->committedText.assign(dynamicBuffer.data(), static_cast<size_t>(length));
            context->preeditText.clear();
            context->composing = false;
            context->lastKeyEventSummary = buildSummary(length, dynamicBuffer.data());
            return true;
        }
        context->lastKeyEventSummary = buildSummary(length, dynamicBuffer.data());
        return false;
    }

    if ((status == XLookupChars || status == XLookupBoth) && length > 0
            && !containsControlCharacters(inlineBuffer, length)) {
        context->committedText.assign(inlineBuffer, static_cast<size_t>(length));
        context->preeditText.clear();
        context->composing = false;
        context->lastKeyEventSummary = buildSummary(length, inlineBuffer);
        return true;
    }

    context->lastKeyEventSummary = buildSummary(length, inlineBuffer);

    return false;
}

jstring consumeJavaString(JNIEnv* env,
                          std::string& value) {
    if (value.empty()) {
        return nullptr;
    }

    jstring result = env->NewStringUTF(value.c_str());
    value.clear();
    return result;
}

std::string contextDebugSummary(LinuxXimContext* context) {
    if (context == nullptr) {
        return "context=null requestedModifiers=" + g_requested_locale_modifiers
                + " appliedModifiers=" + g_applied_locale_modifiers;
    }

    std::ostringstream out;
    out << "requestedModifiers=" << context->requestedLocaleModifiers
        << " appliedModifiers=" << context->appliedLocaleModifiers
        << " style=" << describeStyle(context->inputStyle)
        << " filterMask=" << describeMask(context->filterEventMask)
        << " originalMask=" << describeMask(context->originalEventMask)
        << " lastKeyEvent=" << context->lastKeyEventSummary;
    return out.str();
}
#endif

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_LinuxXimNative_nativeIsSupported(JNIEnv*, jclass) {
#if SSOPTIMIZER_NATIVE_X11_AVAILABLE
    ensureLocaleConfigured();
    if (!XSupportsLocale()) {
        setLastError("XSupportsLocale() returned false");
        return JNI_FALSE;
    }
    clearLastError();
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_LinuxXimNative_nativeLastErrorMessage(JNIEnv* env,
                                                                                   jclass) {
#if SSOPTIMIZER_NATIVE_X11_AVAILABLE
    return env->NewStringUTF(g_last_error.c_str());
#else
    return env->NewStringUTF("X11 support not compiled in native runtime");
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_LinuxXimNative_nativeDebugSummary(JNIEnv* env,
                                                                               jclass,
                                                                               jlong handle) {
#if SSOPTIMIZER_NATIVE_X11_AVAILABLE
    auto* context = reinterpret_cast<LinuxXimContext*>(static_cast<std::uintptr_t>(handle));
    const std::string summary = contextDebugSummary(context);
    return env->NewStringUTF(summary.c_str());
#else
    (void) handle;
    return env->NewStringUTF("X11 support not compiled in native runtime");
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_LinuxXimNative_nativeCreateContext(JNIEnv*,
                                                                                jclass,
                                                                                jlong display,
                                                                                jlong window,
                                                                                jint style) {
#if SSOPTIMIZER_NATIVE_X11_AVAILABLE
    LinuxXimContext* context = createContext(display, window, style);
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(context));
#else
    (void) display;
    (void) window;
    (void) style;
    return 0L;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_LinuxXimNative_nativeDestroyContext(JNIEnv*,
                                                                                 jclass,
                                                                                 jlong handle) {
#if SSOPTIMIZER_NATIVE_X11_AVAILABLE
    auto* context = reinterpret_cast<LinuxXimContext*>(static_cast<std::uintptr_t>(handle));
    destroyContext(context);
#else
    (void) handle;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_LinuxXimNative_nativeFocusIn(JNIEnv*,
                                                                          jclass,
                                                                          jlong handle) {
#if SSOPTIMIZER_NATIVE_X11_AVAILABLE
    auto* context = reinterpret_cast<LinuxXimContext*>(static_cast<std::uintptr_t>(handle));
    if (context != nullptr && context->xic != nullptr) {
        XSetICFocus(context->xic);
    }
#else
    (void) handle;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_LinuxXimNative_nativeFocusOut(JNIEnv*,
                                                                           jclass,
                                                                           jlong handle) {
#if SSOPTIMIZER_NATIVE_X11_AVAILABLE
    auto* context = reinterpret_cast<LinuxXimContext*>(static_cast<std::uintptr_t>(handle));
    if (context != nullptr && context->xic != nullptr) {
        XUnsetICFocus(context->xic);
        context->preeditText.clear();
        context->composing = false;
    }
#else
    (void) handle;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_LinuxXimNative_nativeUpdateSpot(JNIEnv*,
                                                                             jclass,
                                                                             jlong handle,
                                                                             jint x,
                                                                             jint y,
                                                                             jint height) {
#if SSOPTIMIZER_NATIVE_X11_AVAILABLE
    auto* context = reinterpret_cast<LinuxXimContext*>(static_cast<std::uintptr_t>(handle));
    updateSpot(context, x, y, height);
#else
    (void) handle;
    (void) x;
    (void) y;
    (void) height;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_LinuxXimNative_nativeHandleKeyEvent(JNIEnv*,
                                                                                 jclass,
                                                                                 jlong handle,
                                                                                 jlong keyEventAddress,
                                                                                 jint eventType) {
#if SSOPTIMIZER_NATIVE_X11_AVAILABLE
    auto* context = reinterpret_cast<LinuxXimContext*>(static_cast<std::uintptr_t>(handle));
    auto* event = reinterpret_cast<XEvent*>(static_cast<std::uintptr_t>(keyEventAddress));
    return handleKeyEvent(context, event, eventType) ? JNI_TRUE : JNI_FALSE;
#else
    (void) handle;
    (void) keyEventAddress;
    (void) eventType;
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_LinuxXimNative_nativeLastKeyEventSummary(JNIEnv* env,
                                                                                      jclass,
                                                                                      jlong handle) {
#if SSOPTIMIZER_NATIVE_X11_AVAILABLE
    auto* context = reinterpret_cast<LinuxXimContext*>(static_cast<std::uintptr_t>(handle));
    if (context == nullptr) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(context->lastKeyEventSummary.c_str());
#else
    (void) handle;
    return env->NewStringUTF("");
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_LinuxXimNative_nativePollCommittedText(JNIEnv* env,
                                                                                    jclass,
                                                                                    jlong handle) {
#if SSOPTIMIZER_NATIVE_X11_AVAILABLE
    auto* context = reinterpret_cast<LinuxXimContext*>(static_cast<std::uintptr_t>(handle));
    if (context == nullptr) {
        return nullptr;
    }
    return consumeJavaString(env, context->committedText);
#else
    (void) env;
    (void) handle;
    return nullptr;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_LinuxXimNative_nativeCurrentPreeditText(JNIEnv* env,
                                                                                     jclass,
                                                                                     jlong handle) {
#if SSOPTIMIZER_NATIVE_X11_AVAILABLE
    auto* context = reinterpret_cast<LinuxXimContext*>(static_cast<std::uintptr_t>(handle));
    if (context == nullptr || context->preeditText.empty()) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(context->preeditText.c_str());
#else
    (void) handle;
    return env->NewStringUTF("");
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_LinuxXimNative_nativeIsComposing(JNIEnv*,
                                                                              jclass,
                                                                              jlong handle) {
#if SSOPTIMIZER_NATIVE_X11_AVAILABLE
    auto* context = reinterpret_cast<LinuxXimContext*>(static_cast<std::uintptr_t>(handle));
    return (context != nullptr && context->composing) ? JNI_TRUE : JNI_FALSE;
#else
    (void) handle;
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_github_kasuminova_ssoptimizer_common_input_ime_LinuxXimNative_nativeFilterXimProtocolEvent(
        JNIEnv*,
        jclass,
        jlong handle,
        jlong eventAddress) {
#if SSOPTIMIZER_NATIVE_X11_AVAILABLE
    auto* context = reinterpret_cast<LinuxXimContext*>(static_cast<std::uintptr_t>(handle));
    auto* event = reinterpret_cast<XEvent*>(static_cast<std::uintptr_t>(eventAddress));
    if (context == nullptr || context->xic == nullptr || event == nullptr) {
        return JNI_FALSE;
    }
    return XFilterEvent(event, None) ? JNI_TRUE : JNI_FALSE;
#else
    (void) handle;
    (void) eventAddress;
    return JNI_FALSE;
#endif
}