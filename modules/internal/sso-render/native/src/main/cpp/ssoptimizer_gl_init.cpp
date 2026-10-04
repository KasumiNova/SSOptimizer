/**
 * GL 函数指针初始化（glad）。
 *
 * Windows 的 opengl32.dll 仅导出 GL 1.1 符号，1.2+ 函数（glBindBuffer、glBlendEquation 等）
 * 必须运行时取指针；Linux 侧同样经 glad 统一入口，避免直接符号链接的平台分歧。
 * gladLoadGL 自带平台 loader（Windows: wglGetProcAddress + opengl32 GetProcAddress 兜底；
 * Linux: dlopen libGL + glXGetProcAddress），但内部经 glGetString(GL_VERSION) 探测版本
 * 决定加载范围——调用线程必须持有当前 GL context，否则版本串为 NULL、全部指针留空。
 */
#include "github_kasuminova_ssoptimizer_common_render_runtime_NativeRuntime.h"
#include <glad/glad.h>

extern "C" {

JNIEXPORT jboolean JNICALL Java_github_kasuminova_ssoptimizer_common_render_runtime_NativeRuntime_nativeInitGl(
        JNIEnv*, jclass) {
    static int loadResult = -1;
    if (loadResult < 0) {
        loadResult = gladLoadGL() ? 1 : 0;
    }
    return loadResult == 1 ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
