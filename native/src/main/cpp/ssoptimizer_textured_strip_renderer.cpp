#include "github_kasuminova_ssoptimizer_render_engine_TexturedStripRenderHelper.h"
#include "ssoptimizer_render_common.h"

using namespace ssoptimizer::render;

extern "C" {

JNIEXPORT void JNICALL Java_github_kasuminova_ssoptimizer_render_engine_TexturedStripRenderHelper_nativeRenderTexturedStrip(
    JNIEnv*, jclass,
    jfloat startX, jfloat startY,
    jfloat endX, jfloat endY,
    jfloat startWidth, jfloat endWidth,
    jint red, jint green, jint blue, jint alpha,
    jfloat startEdgeAlphaScale,
    jfloat centerAlphaScale,
    jfloat endEdgeAlphaScale,
    jboolean) {
    StripOuterVertices outer{};
    computeTexturedStripOuterVertices(startX, startY, endX, endY, startWidth, endWidth, outer);

    const float centerX = (startX + endX) * 0.5f;
    const float centerY = (startY + endY) * 0.5f;
    const GLubyte centerAlpha = scaleAlphaToByte(alpha, centerAlphaScale);
    const GLubyte startAlpha = scaleAlphaToByte(alpha, startEdgeAlphaScale);
    const GLubyte endAlpha = scaleAlphaToByte(alpha, endEdgeAlphaScale);
    const GLubyte redByte = static_cast<GLubyte>(red & 0xFF);
    const GLubyte greenByte = static_cast<GLubyte>(green & 0xFF);
    const GLubyte blueByte = static_cast<GLubyte>(blue & 0xFF);

    const GLfloat vertices[] = {
        centerX, centerY,
        outer.x[0], outer.y[0],
        outer.x[1], outer.y[1],
        outer.x[2], outer.y[2],
        outer.x[3], outer.y[3],
        outer.x[0], outer.y[0]
    };
    const GLfloat texCoords[] = {
        0.5f, 0.5f,
        0.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f,
        1.0f, 0.0f,
        0.0f, 0.0f
    };
    const GLubyte colors[] = {
        redByte, greenByte, blueByte, centerAlpha,
        redByte, greenByte, blueByte, startAlpha,
        redByte, greenByte, blueByte, startAlpha,
        redByte, greenByte, blueByte, endAlpha,
        redByte, greenByte, blueByte, endAlpha,
        redByte, greenByte, blueByte, startAlpha
    };
    const GLubyte finalColor[] = {redByte, greenByte, blueByte, startAlpha};

    drawColoredTexturedArray(GL_TRIANGLE_FAN, vertices, texCoords, colors, 6, finalColor);
}

} // extern "C"