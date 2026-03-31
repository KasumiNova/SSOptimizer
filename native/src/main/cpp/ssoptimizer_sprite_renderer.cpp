#include "github_kasuminova_ssoptimizer_render_engine_SpriteRenderHelper.h"
#include "ssoptimizer_render_common.h"

using namespace ssoptimizer::render;

extern "C" {

JNIEXPORT void JNICALL Java_github_kasuminova_ssoptimizer_render_engine_SpriteRenderHelper_nativeRenderSprite(
        JNIEnv*, jclass,
        jfloat posX, jfloat posY,
        jfloat width, jfloat height,
        jfloat centerX, jfloat centerY,
        jfloat angle,
        jint colorR, jint colorG, jint colorB, jint colorA,
        jint blendSrc, jint blendDest,
        jfloat texX, jfloat texY, jfloat texWidth, jfloat texHeight) {

    const float cx = (centerX != -1.0f && centerY != -1.0f) ? centerX : width * 0.5f;
    const float cy = (centerX != -1.0f && centerY != -1.0f) ? centerY : height * 0.5f;

    QuadVertices quad{};
    computeSpriteQuad(posX, posY, width, height, cx, cy, angle, quad);

    setCurrentColor(colorR, colorG, colorB, static_cast<GLubyte>(colorA & 0xFF));

    glEnable(GL_TEXTURE_2D);
    glEnable(GL_BLEND);
    glBlendFunc(static_cast<GLenum>(blendSrc), static_cast<GLenum>(blendDest));

    glBegin(GL_QUADS);
    glTexCoord2f(texX, texY);                           glVertex2f(quad.x[0], quad.y[0]);
    glTexCoord2f(texX, texY + texHeight);               glVertex2f(quad.x[1], quad.y[1]);
    glTexCoord2f(texX + texWidth, texY + texHeight);    glVertex2f(quad.x[2], quad.y[2]);
    glTexCoord2f(texX + texWidth, texY);                glVertex2f(quad.x[3], quad.y[3]);
    glEnd();

    glDisable(GL_BLEND);
}

} // extern "C"