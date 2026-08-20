#include "github_kasuminova_ssoptimizer_common_render_engine_TexturedStripRenderHelper.h"
#include "ssoptimizer_render_common.h"

#include <vector>

using namespace ssoptimizer::render;

namespace {

constexpr int FLOATS_PER_STRIP = 9;
// 每条带的三角扇（中心 + 4 角点 + 闭合顶点）展开为 4 个三角形、12 个顶点。
constexpr int VERTICES_PER_STRIP = 12;

} // namespace

extern "C" {

JNIEXPORT void JNICALL Java_github_kasuminova_ssoptimizer_common_render_engine_TexturedStripRenderHelper_nativeRenderTexturedStrip(
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

// 帧内批量渲染：将一帧收集到的所有任务连线条带展开为 GL_TRIANGLES 后单次 draw call 提交。
// geometry 布局：每条带 9 个 float（startX, startY, endX, endY, startWidth, endWidth,
// startEdgeAlphaScale, centerAlphaScale, endEdgeAlphaScale）。
// colors 布局：每条带 1 个 0xRRGGBBAA 打包 int。
JNIEXPORT void JNICALL Java_github_kasuminova_ssoptimizer_common_render_engine_TexturedStripRenderHelper_nativeRenderTexturedStripBatch(
    JNIEnv* env, jclass,
    jfloatArray geometryArray,
    jintArray colorArray,
    jint stripCount) {
    if (stripCount <= 0) {
        return;
    }

    jfloat* geometry = env->GetFloatArrayElements(geometryArray, nullptr);
    jint* packedColors = env->GetIntArrayElements(colorArray, nullptr);

    std::vector<GLfloat> vertices;
    std::vector<GLfloat> texCoords;
    std::vector<GLubyte> colors;
    vertices.reserve(static_cast<size_t>(stripCount) * VERTICES_PER_STRIP * 2);
    texCoords.reserve(static_cast<size_t>(stripCount) * VERTICES_PER_STRIP * 2);
    colors.reserve(static_cast<size_t>(stripCount) * VERTICES_PER_STRIP * 4);

    GLubyte lastRed = 0;
    GLubyte lastGreen = 0;
    GLubyte lastBlue = 0;
    GLubyte lastStartAlpha = 0;

    for (jint strip = 0; strip < stripCount; strip++) {
        const jint base = strip * FLOATS_PER_STRIP;
        const float startX = geometry[base];
        const float startY = geometry[base + 1];
        const float endX = geometry[base + 2];
        const float endY = geometry[base + 3];
        const float startWidth = geometry[base + 4];
        const float endWidth = geometry[base + 5];
        const float startEdgeAlphaScale = geometry[base + 6];
        const float centerAlphaScale = geometry[base + 7];
        const float endEdgeAlphaScale = geometry[base + 8];

        const jint packed = packedColors[strip];
        const GLubyte redByte = static_cast<GLubyte>((packed >> 24) & 0xFF);
        const GLubyte greenByte = static_cast<GLubyte>((packed >> 16) & 0xFF);
        const GLubyte blueByte = static_cast<GLubyte>((packed >> 8) & 0xFF);
        const int alpha = packed & 0xFF;

        StripOuterVertices outer{};
        computeTexturedStripOuterVertices(startX, startY, endX, endY, startWidth, endWidth, outer);

        const float centerX = (startX + endX) * 0.5f;
        const float centerY = (startY + endY) * 0.5f;
        const GLubyte centerAlpha = scaleAlphaToByte(alpha, centerAlphaScale);
        const GLubyte startAlpha = scaleAlphaToByte(alpha, startEdgeAlphaScale);
        const GLubyte endAlpha = scaleAlphaToByte(alpha, endEdgeAlphaScale);

        // 扇形顶点：c, s0, s1, e1, e0；展开为 (c,s0,s1) (c,s1,e1) (c,e1,e0) (c,e0,s0)。
        const GLfloat fanX[5] = {centerX, outer.x[0], outer.x[1], outer.x[2], outer.x[3]};
        const GLfloat fanY[5] = {centerY, outer.y[0], outer.y[1], outer.y[2], outer.y[3]};
        const GLfloat fanU[5] = {0.5f, 0.0f, 0.0f, 1.0f, 1.0f};
        const GLfloat fanV[5] = {0.5f, 0.0f, 1.0f, 1.0f, 0.0f};
        const GLubyte fanAlpha[5] = {centerAlpha, startAlpha, startAlpha, endAlpha, endAlpha};
        const int triangleIndices[VERTICES_PER_STRIP] = {
            0, 1, 2,
            0, 2, 3,
            0, 3, 4,
            0, 4, 1
        };

        for (int i = 0; i < VERTICES_PER_STRIP; i++) {
            const int fanIndex = triangleIndices[i];
            vertices.push_back(fanX[fanIndex]);
            vertices.push_back(fanY[fanIndex]);
            texCoords.push_back(fanU[fanIndex]);
            texCoords.push_back(fanV[fanIndex]);
            colors.push_back(redByte);
            colors.push_back(greenByte);
            colors.push_back(blueByte);
            colors.push_back(fanAlpha[fanIndex]);
        }

        lastRed = redByte;
        lastGreen = greenByte;
        lastBlue = blueByte;
        lastStartAlpha = startAlpha;
    }

    env->ReleaseFloatArrayElements(geometryArray, geometry, JNI_ABORT);
    env->ReleaseIntArrayElements(colorArray, packedColors, JNI_ABORT);

    const GLubyte finalColor[] = {lastRed, lastGreen, lastBlue, lastStartAlpha};
    drawColoredTexturedArray(GL_TRIANGLES, vertices.data(), texCoords.data(), colors.data(),
                             stripCount * VERTICES_PER_STRIP, finalColor);
}

} // extern "C"
