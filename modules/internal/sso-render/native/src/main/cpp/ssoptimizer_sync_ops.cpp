#include "github_kasuminova_ssoptimizer_bridge_opengl_NativeSyncOpsImpl.h"
#include <glad/glad.h>

extern "C" {

JNIEXPORT jboolean JNICALL Java_github_kasuminova_ssoptimizer_bridge_opengl_NativeSyncOpsImpl_nativeSyncOpsSupported(
        JNIEnv*, jclass) {
    return (glad_glFenceSync != nullptr
            && glad_glWaitSync != nullptr
            && glad_glClientWaitSync != nullptr
            && glad_glDeleteSync != nullptr
            && glad_glMemoryBarrier != nullptr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_github_kasuminova_ssoptimizer_bridge_opengl_NativeSyncOpsImpl_nativeFenceSync(
        JNIEnv*, jclass, jint condition, jint flags) {
    return reinterpret_cast<jlong>(glad_glFenceSync(
            static_cast<GLenum>(condition), static_cast<GLbitfield>(flags)));
}

JNIEXPORT void JNICALL Java_github_kasuminova_ssoptimizer_bridge_opengl_NativeSyncOpsImpl_nativeWaitSync(
        JNIEnv*, jclass, jlong sync, jint flags, jlong timeout) {
    glad_glWaitSync(reinterpret_cast<GLsync>(sync),
                    static_cast<GLbitfield>(flags), static_cast<GLuint64>(timeout));
}

JNIEXPORT jint JNICALL Java_github_kasuminova_ssoptimizer_bridge_opengl_NativeSyncOpsImpl_nativeClientWaitSync(
        JNIEnv*, jclass, jlong sync, jint flags, jlong timeout) {
    return static_cast<jint>(glad_glClientWaitSync(
            reinterpret_cast<GLsync>(sync), static_cast<GLbitfield>(flags), static_cast<GLuint64>(timeout)));
}

JNIEXPORT void JNICALL Java_github_kasuminova_ssoptimizer_bridge_opengl_NativeSyncOpsImpl_nativeDeleteSync(
        JNIEnv*, jclass, jlong sync) {
    glad_glDeleteSync(reinterpret_cast<GLsync>(sync));
}

JNIEXPORT void JNICALL Java_github_kasuminova_ssoptimizer_bridge_opengl_NativeSyncOpsImpl_nativeMemoryBarrier(
        JNIEnv*, jclass, jint barriers) {
    glad_glMemoryBarrier(static_cast<GLbitfield>(barriers));
}

}
