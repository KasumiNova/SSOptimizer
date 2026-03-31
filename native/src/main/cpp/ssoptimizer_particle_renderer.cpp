#include "github_kasuminova_ssoptimizer_render_engine_ParticleBatchHelper.h"
#include "ssoptimizer_render_common.h"

using namespace ssoptimizer::render;

extern "C" {

JNIEXPORT void JNICALL Java_github_kasuminova_ssoptimizer_render_engine_ParticleBatchHelper_nativeRenderImmediateQuads(
        JNIEnv* env, jclass,
        jobject colorBuf,
        jobject vertexBuf,
        jobject texCoordBuf,
        jint numVertices) {

    auto* colors = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(colorBuf));
    auto* vertices = static_cast<float*>(env->GetDirectBufferAddress(vertexBuf));
    auto* texCoords = static_cast<float*>(env->GetDirectBufferAddress(texCoordBuf));

    if (colors == nullptr || vertices == nullptr || texCoords == nullptr || numVertices <= 0) {
        return;
    }

    drawColoredTexturedImmediate(vertices, texCoords, colors, numVertices);
}

} // extern "C"