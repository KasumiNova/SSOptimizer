/**
 * 引擎喇叭图条批量渲染器（JNI 实现）。
 *
 * 对应 Java 类: EngineRenderHelper
 * 将原版 immediate-mode GL 调用替换为批量 Vertex Array 渲染，
 * 减少 draw call 数量以提升引擎喇叭渲染性能。
 */
#include "github_kasuminova_ssoptimizer_common_render_engine_EngineRenderHelper.h"
#include "ssoptimizer_render_common.h"

using namespace ssoptimizer::render;

extern "C" {

JNIEXPORT void JNICALL Java_github_kasuminova_ssoptimizer_common_render_engine_EngineRenderHelper_nativeRenderEngineStripBatch(
        JNIEnv*, jclass,
        jfloat posX, jfloat posY,
        jfloat angle,
        jfloat angularRotation,
        jfloat spreadRotation,
        jint passCount,
        jint layerCount,
        jfloat texUStart,
        jfloat texSpan,
        jfloat textureAdvance,
        jfloat innerLength,
        jfloat stripLength,
        jfloat stripWidth,
        jint red, jint green, jint blue,
        jfloat colorAlphaScale,
        jboolean exactAlphaPath) {
    if (passCount <= 0 || layerCount <= 0) {
        return;
    }

    const float passCountF = static_cast<float>(passCount);
    const float texUStep = 1.0f / passCountF;
    float texU = texUStart;
    const GLubyte redByte = static_cast<GLubyte>(red & 0xFF);
    const GLubyte greenByte = static_cast<GLubyte>(green & 0xFF);
    const GLubyte blueByte = static_cast<GLubyte>(blue & 0xFF);
    const GLubyte finalColor[] = {redByte, greenByte, blueByte, 0};

    const GLubyte midAlpha = clampToByte(static_cast<int>(100.0f * colorAlphaScale));
    for (int layer = 0; layer < layerCount; layer++) {
        for (int passIndex = 0; passIndex < passCount; passIndex++) {
            GLfloat vertices[12];
            computeStripPassVertices(posX, posY, angle, angularRotation, spreadRotation,
                passCountF, passIndex, innerLength, stripLength, stripWidth, vertices);

            const GLfloat texCoords[] = {
                texU, TEX_MIN,
                texU, TEX_MAX,
                texU + texSpan, TEX_MIN,
                texU + texSpan, TEX_MAX,
                texU + textureAdvance, TEX_MIN,
                texU + textureAdvance, TEX_MAX
            };
            const GLubyte startAlpha = clampToByte(static_cast<int>(static_cast<float>(passIndex) * 5.0f * colorAlphaScale));
            const GLubyte colors[] = {
                redByte, greenByte, blueByte, startAlpha,
                redByte, greenByte, blueByte, startAlpha,
                redByte, greenByte, blueByte, midAlpha,
                redByte, greenByte, blueByte, midAlpha,
                redByte, greenByte, blueByte, 0,
                redByte, greenByte, blueByte, 0
            };

            if (exactAlphaPath == JNI_TRUE) {
                drawColoredTexturedImmediate(vertices, texCoords, colors, 6);
            } else {
                drawColoredTexturedArray(GL_QUAD_STRIP, vertices, texCoords, colors, 6, finalColor);
            }
            texU += texUStep;
        }
    }
}

JNIEXPORT void JNICALL Java_github_kasuminova_ssoptimizer_common_render_engine_EngineRenderHelper_nativeRenderEngineCorePass(
        JNIEnv*, jclass,
        jfloat posX, jfloat posY,
        jfloat angle,
        jfloat stateRotation,
        jfloat omegaRotation,
        jfloat stripLength,
        jfloat stripWidth,
        jint red, jint green, jint blue,
        jint alpha,
        jboolean exactAlphaPath) {
    GLfloat vertices[8];
    computeCorePassVertices(posX, posY, angle, stateRotation, omegaRotation,
        stripLength, stripWidth, vertices);

    const GLfloat texCoords[] = {
        TEX_PAD, TEX_MIN,
        TEX_PAD, TEX_MAX,
        1.0f - TEX_PAD, TEX_MIN,
        1.0f - TEX_PAD, TEX_MAX
    };
    const GLubyte alphaByte = static_cast<GLubyte>(alpha & 0xFF);
    if (exactAlphaPath == JNI_TRUE) {
        glBegin(GL_QUAD_STRIP);
        glColor4ub(static_cast<GLubyte>(red & 0xFF), static_cast<GLubyte>(green & 0xFF), static_cast<GLubyte>(blue & 0xFF), alphaByte);
        glTexCoord2f(TEX_PAD, TEX_MIN);
        glVertex2f(vertices[0], vertices[1]);
        glTexCoord2f(TEX_PAD, TEX_MAX);
        glVertex2f(vertices[2], vertices[3]);
        glTexCoord2f(1.0f - TEX_PAD, TEX_MIN);
        glVertex2f(vertices[4], vertices[5]);
        glTexCoord2f(1.0f - TEX_PAD, TEX_MAX);
        glVertex2f(vertices[6], vertices[7]);
        glEnd();
    } else {
        setCurrentColor(red, green, blue, alphaByte);
        drawTexturedArray(GL_QUAD_STRIP, vertices, texCoords, 4);
    }
}

} // extern "C"