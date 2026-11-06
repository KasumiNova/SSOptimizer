#include "github_kasuminova_ssoptimizer_common_render_spritebatch_SpriteBatchNative.h"
#include "ssoptimizer_render_common.h"

#include <cstring>

#ifndef GL_BLEND_EQUATION_RGB
#define GL_BLEND_EQUATION_RGB 0x8009
#endif
#ifndef GL_FRAMEBUFFER_BINDING
#define GL_FRAMEBUFFER_BINDING 0x8CA6
#endif

using namespace ssoptimizer::render;

namespace {

constexpr jint RESULT_GUARD_REJECTED    = -1;
constexpr jint RESULT_EQUATION_MISMATCH = -2;
constexpr jint RESULT_INVALID_BUFFER    = -3;
constexpr jint RESULT_EXTENDED_STATE    = -4;
constexpr jint RESULT_STATE_MISMATCH    = -5;

constexpr size_t QUAD_VERTEX_BYTES = 80;
constexpr size_t QUAD_INDEX_BYTES  = 12;

}

extern "C" {

JNIEXPORT jint JNICALL Java_github_kasuminova_ssoptimizer_common_render_spritebatch_SpriteBatchNative_nativeSubmit(
        JNIEnv* env, jclass,
        jobject verts, jobject indices, jobject runTarget,
        jint pendingQuads, jint expectedBlendEquation, jint requireExtendedState,
        jfloat posX, jfloat posY,
        jfloat width, jfloat height,
        jfloat centerX, jfloat centerY,
        jfloat angle,
        jint colorR, jint colorG, jint colorB, jint colorA,
        jfloat texX, jfloat texY, jfloat texWidth, jfloat texHeight) {

    GLint matrixMode = 0;
    glGetIntegerv(GL_MATRIX_MODE, &matrixMode);
    if (matrixMode != GL_MODELVIEW) {
        return RESULT_GUARD_REJECTED;
    }
    // stencil 的读写与其他绘制交错，延迟提交无法保证顺序，必须拒绝合批。
    if (glIsEnabled(GL_STENCIL_TEST) || glIsEnabled(GL_SCISSOR_TEST)) {
        return RESULT_GUARD_REJECTED;
    }

    auto* vertPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(verts));
    auto* idxPtr  = static_cast<uint8_t*>(env->GetDirectBufferAddress(indices));
    auto* targetPtr = static_cast<jint*>(env->GetDirectBufferAddress(runTarget));
    if (vertPtr == nullptr || idxPtr == nullptr || targetPtr == nullptr) {
        return RESULT_INVALID_BUFFER;
    }

    GLint fboBinding = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &fboBinding);
    GLint viewport[4];
    glGetIntegerv(GL_VIEWPORT, viewport);
    if (pendingQuads > 0) {
        if (targetPtr[0] != fboBinding
                || targetPtr[1] != viewport[0] || targetPtr[2] != viewport[1]
                || targetPtr[3] != viewport[2] || targetPtr[4] != viewport[3]) {
            return RESULT_STATE_MISMATCH;
        }
    } else {
        targetPtr[0] = fboBinding;
        targetPtr[1] = viewport[0];
        targetPtr[2] = viewport[1];
        targetPtr[3] = viewport[2];
        targetPtr[4] = viewport[3];
    }

    const bool extendedState = glIsEnabled(GL_ALPHA_TEST) != GL_FALSE;
    if (extendedState) {
        return RESULT_EXTENDED_STATE;
    }
    if (requireExtendedState != 0) {
        return RESULT_STATE_MISMATCH;
    }

    GLint blendEquation = GL_FUNC_ADD;
    glGetIntegerv(GL_BLEND_EQUATION_RGB, &blendEquation);
    if (expectedBlendEquation >= 0 && blendEquation != expectedBlendEquation) {
        return RESULT_EQUATION_MISMATCH;
    }

    GLfloat mv[16];
    GLfloat pj[16];
    glGetFloatv(GL_MODELVIEW_MATRIX, mv);
    glGetFloatv(GL_PROJECTION_MATRIX, pj);

    const float m0  = pj[0] * mv[0]  + pj[4] * mv[1];
    const float m1  = pj[1] * mv[0]  + pj[5] * mv[1];
    const float m4  = pj[0] * mv[4]  + pj[4] * mv[5];
    const float m5  = pj[1] * mv[4]  + pj[5] * mv[5];
    const float m12 = pj[0] * mv[12] + pj[4] * mv[13] + pj[12];
    const float m13 = pj[1] * mv[12] + pj[5] * mv[13] + pj[13];

    const float cx = (centerX != -1.0f && centerY != -1.0f) ? centerX : width * 0.5f;
    const float cy = (centerX != -1.0f && centerY != -1.0f) ? centerY : height * 0.5f;

    QuadVertices quad{};
    computeSpriteQuad(posX, posY, width, height, cx, cy, angle, quad);

    const float uvs[4][2] = {
            {texX,            texY},
            {texX,            texY + texHeight},
            {texX + texWidth, texY + texHeight},
            {texX + texWidth, texY},
    };
    const uint8_t cr = static_cast<uint8_t>(colorR & 0xFF);
    const uint8_t cg = static_cast<uint8_t>(colorG & 0xFF);
    const uint8_t cb = static_cast<uint8_t>(colorB & 0xFF);
    const uint8_t ca = static_cast<uint8_t>(colorA & 0xFF);

    uint8_t* v = vertPtr + static_cast<size_t>(pendingQuads) * QUAD_VERTEX_BYTES;
    for (int i = 0; i < 4; i++) {
        const float x = m0 * quad.x[i] + m4 * quad.y[i] + m12;
        const float y = m1 * quad.x[i] + m5 * quad.y[i] + m13;
        std::memcpy(v, &x, sizeof(float));
        std::memcpy(v + 4, &y, sizeof(float));
        std::memcpy(v + 8, &uvs[i][0], sizeof(float));
        std::memcpy(v + 12, &uvs[i][1], sizeof(float));
        v[16] = cr;
        v[17] = cg;
        v[18] = cb;
        v[19] = ca;
        v += 20;
    }

    auto* idx = reinterpret_cast<uint16_t*>(idxPtr + static_cast<size_t>(pendingQuads) * QUAD_INDEX_BYTES);
    const uint16_t base = static_cast<uint16_t>(pendingQuads * 4);
    idx[0] = base;
    idx[1] = static_cast<uint16_t>(base + 1);
    idx[2] = static_cast<uint16_t>(base + 2);
    idx[3] = base;
    idx[4] = static_cast<uint16_t>(base + 2);
    idx[5] = static_cast<uint16_t>(base + 3);

    return blendEquation;
}

JNIEXPORT void JNICALL Java_github_kasuminova_ssoptimizer_common_render_spritebatch_SpriteBatchNative_nativeFlush(
        JNIEnv*, jclass,
        jint vertexVbo, jlong vertexBase,
        jint indexVbo, jlong indexBase,
        jint quadCount,
        jint texture, jint blendSrc, jint blendDest, jint blendEquation,
        jint fbo, jint viewportX, jint viewportY, jint viewportW, jint viewportH,
        jboolean alphaTestEnabled, jint alphaFunc, jfloat alphaRef,
        jint colorR, jint colorG, jint colorB, jint colorA,
        jint prevMatrixMode) {
    GLint prevFbo = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &prevFbo);
    GLint prevViewport[4];
    glGetIntegerv(GL_VIEWPORT, prevViewport);

    glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_TEXTURE_BIT | GL_CURRENT_BIT);
    glPushClientAttrib(GL_CLIENT_VERTEX_ARRAY_BIT);

    // 延迟 flush 必须回放收集时的 FBO 与 viewport，并在结束后恢复入口状态。
    glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(fbo));
    glViewport(viewportX, viewportY, viewportW, viewportH);

    glEnable(GL_TEXTURE_2D);
    glEnable(GL_BLEND);
    glBlendFunc(static_cast<GLenum>(blendSrc), static_cast<GLenum>(blendDest));
    glBlendEquation(static_cast<GLenum>(blendEquation));
    glDisable(GL_STENCIL_TEST);
    if (alphaTestEnabled == JNI_TRUE) {
        glEnable(GL_ALPHA_TEST);
        glAlphaFunc(static_cast<GLenum>(alphaFunc), alphaRef);
    } else {
        glDisable(GL_ALPHA_TEST);
    }
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(texture));

    glMatrixMode(GL_PROJECTION);
    glPushMatrix();
    glLoadIdentity();
    glMatrixMode(GL_MODELVIEW);
    glPushMatrix();
    glLoadIdentity();

    glEnableClientState(GL_VERTEX_ARRAY);
    glEnableClientState(GL_TEXTURE_COORD_ARRAY);
    glEnableClientState(GL_COLOR_ARRAY);
    glBindBuffer(GL_ARRAY_BUFFER, static_cast<GLuint>(vertexVbo));
    const auto* vertexPtr = reinterpret_cast<const void*>(static_cast<uintptr_t>(vertexBase));
    glVertexPointer(2, GL_FLOAT, 20, vertexPtr);
    glTexCoordPointer(2, GL_FLOAT, 20, reinterpret_cast<const void*>(static_cast<uintptr_t>(vertexBase) + 8));
    glColorPointer(4, GL_UNSIGNED_BYTE, 20, reinterpret_cast<const void*>(static_cast<uintptr_t>(vertexBase) + 16));
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, static_cast<GLuint>(indexVbo));
    glDrawElements(GL_TRIANGLES, quadCount * 6, GL_UNSIGNED_SHORT,
            reinterpret_cast<const void*>(static_cast<uintptr_t>(indexBase)));

    glPopMatrix();
    glMatrixMode(GL_PROJECTION);
    glPopMatrix();
    glMatrixMode(static_cast<GLenum>(prevMatrixMode));

    glPopClientAttrib();
    glPopAttrib();
    glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(prevFbo));
    glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
    // glad 不更新 LWJGL2 StateTracker，VBO 绑定必须由 Java 侧经 LWJGL 恢复。
    glColor4ub(static_cast<GLubyte>(colorR & 0xFF),
               static_cast<GLubyte>(colorG & 0xFF),
               static_cast<GLubyte>(colorB & 0xFF),
               static_cast<GLubyte>(colorA & 0xFF));
}

}
