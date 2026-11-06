#include "github_kasuminova_ssoptimizer_common_render_runtime_NativeRuntime.h"
#include <glad/glad.h>

extern "C" {

JNIEXPORT jint JNICALL Java_github_kasuminova_ssoptimizer_common_render_runtime_NativeRuntime_nativeRenderAbiVersion(
        JNIEnv*, jclass) {
    return static_cast<jint>(github_kasuminova_ssoptimizer_common_render_runtime_NativeRuntime_NATIVE_ABI_VERSION);
}

JNIEXPORT jboolean JNICALL Java_github_kasuminova_ssoptimizer_common_render_runtime_NativeRuntime_nativeInitGl(
        JNIEnv*, jclass) {
    static int loadResult = -1;
    if (loadResult < 0) {
        loadResult = gladLoadGL() ? 1 : 0;
    }
    return loadResult == 1 ? JNI_TRUE : JNI_FALSE;
}

}
