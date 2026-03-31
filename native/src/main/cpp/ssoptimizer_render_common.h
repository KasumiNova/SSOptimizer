#pragma once

#include <cmath>
#include <cstdint>

#if defined(__SSE2__) && (defined(__x86_64__) || defined(_M_X64) || defined(__i386) || defined(_M_IX86))
#include <emmintrin.h>
#endif

#include <GL/gl.h>

namespace ssoptimizer::render {

constexpr float DEG_TO_RAD = 0.017453292519943295769f;
constexpr float TEX_MIN = 0.01f;
constexpr float TEX_MAX = 0.99f;
constexpr float TEX_PAD = 0.01f;

struct QuadVertices {
    float x[4];
    float y[4];
};

struct StripOuterVertices {
    float x[4];
    float y[4];
};

inline GLubyte clampToByte(const int value) {
    if (value < 0) {
        return 0;
    }
    if (value > 255) {
        return 255;
    }
    return static_cast<GLubyte>(value);
}

inline GLubyte scaleAlphaToByte(const int alpha, const float scale) {
    return clampToByte(static_cast<int>(static_cast<float>(alpha) * scale));
}

inline void setCurrentColor(const int red, const int green, const int blue, const GLubyte alpha) {
    glColor4ub(static_cast<GLubyte>(red & 0xFF),
               static_cast<GLubyte>(green & 0xFF),
               static_cast<GLubyte>(blue & 0xFF),
               alpha);
}

inline void drawTexturedArray(const GLenum mode,
                              const GLfloat* vertices,
                              const GLfloat* texCoords,
                              const GLsizei vertexCount) {
    glPushClientAttrib(GL_CLIENT_VERTEX_ARRAY_BIT);
    glEnableClientState(GL_VERTEX_ARRAY);
    glEnableClientState(GL_TEXTURE_COORD_ARRAY);
    glVertexPointer(2, GL_FLOAT, 0, vertices);
    glTexCoordPointer(2, GL_FLOAT, 0, texCoords);
    glDrawArrays(mode, 0, vertexCount);
    glPopClientAttrib();
}

inline void drawColoredTexturedArray(const GLenum mode,
                                     const GLfloat* vertices,
                                     const GLfloat* texCoords,
                                     const GLubyte* colors,
                                     const GLsizei vertexCount,
                                     const GLubyte finalColor[4]) {
    glPushClientAttrib(GL_CLIENT_VERTEX_ARRAY_BIT);
    glEnableClientState(GL_COLOR_ARRAY);
    glEnableClientState(GL_VERTEX_ARRAY);
    glEnableClientState(GL_TEXTURE_COORD_ARRAY);
    glColorPointer(4, GL_UNSIGNED_BYTE, 0, colors);
    glVertexPointer(2, GL_FLOAT, 0, vertices);
    glTexCoordPointer(2, GL_FLOAT, 0, texCoords);
    glDrawArrays(mode, 0, vertexCount);
    glPopClientAttrib();
    glColor4ub(finalColor[0], finalColor[1], finalColor[2], finalColor[3]);
}

inline void drawColoredTexturedImmediate(const GLfloat* vertices,
                                         const GLfloat* texCoords,
                                         const GLubyte* colors,
                                         const GLsizei vertexCount) {
    glBegin(GL_QUADS);
    for (GLsizei i = 0; i < vertexCount; i++) {
        const int colorIndex = i * 4;
        const int coordIndex = i * 2;
        glColor4ub(colors[colorIndex],
                   colors[colorIndex + 1],
                   colors[colorIndex + 2],
                   colors[colorIndex + 3]);
        glTexCoord2f(texCoords[coordIndex], texCoords[coordIndex + 1]);
        glVertex2f(vertices[coordIndex], vertices[coordIndex + 1]);
    }
    glEnd();
}

inline void computeSinCos(const float angleDeg, float& sinOut, float& cosOut) {
    const float radians = angleDeg * DEG_TO_RAD;
#if defined(__GNUC__) || defined(__clang__)
    __builtin_sincosf(radians, &sinOut, &cosOut);
#else
    sinOut = std::sin(radians);
    cosOut = std::cos(radians);
#endif
}

inline void computeSpriteQuadScalar(const float posX, const float posY,
                                    const float width, const float height,
                                    const float cx, const float cy,
                                    const float angle,
                                    QuadVertices& quad) {
    const float originX = posX + width * 0.5f;
    const float originY = posY + height * 0.5f;

    if (angle == 0.0f) {
        const float x0 = originX - cx;
        const float y0 = originY - cy;
        const float x1 = x0 + width;
        const float y1 = y0 + height;

        quad.x[0] = x0; quad.y[0] = y0;
        quad.x[1] = x0; quad.y[1] = y1;
        quad.x[2] = x1; quad.y[2] = y1;
        quad.x[3] = x1; quad.y[3] = y0;
        return;
    }

    float sinA;
    float cosA;
    computeSinCos(angle, sinA, cosA);

    const float localX[4] = {-cx, -cx, width - cx, width - cx};
    const float localY[4] = {-cy, height - cy, height - cy, -cy};

    for (int i = 0; i < 4; i++) {
        quad.x[i] = originX + localX[i] * cosA - localY[i] * sinA;
        quad.y[i] = originY + localX[i] * sinA + localY[i] * cosA;
    }
}

#if defined(__SSE2__) && (defined(__x86_64__) || defined(_M_X64) || defined(__i386) || defined(_M_IX86))
inline void computeSpriteQuadSimd(const float posX, const float posY,
                                  const float width, const float height,
                                  const float cx, const float cy,
                                  const float angle,
                                  QuadVertices& quad) {
    const float originX = posX + width * 0.5f;
    const float originY = posY + height * 0.5f;

    if (angle == 0.0f) {
        computeSpriteQuadScalar(posX, posY, width, height, cx, cy, angle, quad);
        return;
    }

    float sinA;
    float cosA;
    computeSinCos(angle, sinA, cosA);

    const __m128 localX = _mm_setr_ps(-cx, -cx, width - cx, width - cx);
    const __m128 localY = _mm_setr_ps(-cy, height - cy, height - cy, -cy);
    const __m128 originXV = _mm_set1_ps(originX);
    const __m128 originYV = _mm_set1_ps(originY);
    const __m128 sinV = _mm_set1_ps(sinA);
    const __m128 cosV = _mm_set1_ps(cosA);

    const __m128 rotatedX = _mm_add_ps(originXV,
            _mm_sub_ps(_mm_mul_ps(localX, cosV), _mm_mul_ps(localY, sinV)));
    const __m128 rotatedY = _mm_add_ps(originYV,
            _mm_add_ps(_mm_mul_ps(localX, sinV), _mm_mul_ps(localY, cosV)));

    _mm_storeu_ps(quad.x, rotatedX);
    _mm_storeu_ps(quad.y, rotatedY);
}
#endif

inline void computeSpriteQuad(const float posX, const float posY,
                              const float width, const float height,
                              const float cx, const float cy,
                              const float angle,
                              QuadVertices& quad) {
#if defined(__SSE2__) && (defined(__x86_64__) || defined(_M_X64) || defined(__i386) || defined(_M_IX86))
    computeSpriteQuadSimd(posX, posY, width, height, cx, cy, angle, quad);
#else
    computeSpriteQuadScalar(posX, posY, width, height, cx, cy, angle, quad);
#endif
}

inline void computeStripPassVertices(const float posX, const float posY,
                                     const float angle,
                                     const float angularRotation,
                                     const float spreadRotation,
                                     const float passCount,
                                     const int passIndex,
                                     const float innerLength,
                                     const float stripLength,
                                     const float stripWidth,
                                     GLfloat* verticesOut) {
    const float passIndexF = static_cast<float>(passIndex);
    const float rotation1 = passCount <= 1.0f
            ? angularRotation
            : ((passCount - passIndexF - 1.0f) / passCount) * angularRotation;
    const float direction = (passIndex % 2 == 0) ? -1.0f : 1.0f;
    const float phase = (passIndexF + 1.0f) / 2.0f;
    const float halfPassCount = passCount / 2.0f;
    const float rotation2 = ((halfPassCount - phase - 1.0f) / halfPassCount) * direction * 2.0f * spreadRotation;
    const float translateX = ((passCount - passIndexF - 1.0f) * innerLength) / (passCount * 2.0f);
    const float scaleX = 0.5f + ((passIndexF + 1.0f) / passCount);
    const float scaleY = 1.0f - ((passCount - passIndexF) / passCount);
    const float scaledHalfWidth = (stripWidth * 0.5f) * scaleY;
    const float lengths[3] = {0.0f, innerLength, stripLength};

    float sinA;
    float cosA;
    computeSinCos(angle + rotation1 + rotation2, sinA, cosA);

    for (int i = 0; i < 3; i++) {
        const float localX = lengths[i] * scaleX + translateX;
        const float rotatedBaseX = localX * cosA;
        const float rotatedBaseY = localX * sinA;
        const int index = i * 4;

        verticesOut[index] = posX + rotatedBaseX + scaledHalfWidth * sinA;
        verticesOut[index + 1] = posY + rotatedBaseY - scaledHalfWidth * cosA;
        verticesOut[index + 2] = posX + rotatedBaseX - scaledHalfWidth * sinA;
        verticesOut[index + 3] = posY + rotatedBaseY + scaledHalfWidth * cosA;
    }
}

inline void computeCorePassVertices(const float posX, const float posY,
                                    const float angle,
                                    const float stateRotation,
                                    const float omegaRotation,
                                    const float stripLength,
                                    const float stripWidth,
                                    GLfloat* verticesOut) {
    const float halfWidth = stripWidth * 0.5f;
    const float localVertices[8] = {
            0.0f, -halfWidth,
            0.0f, halfWidth,
            stripLength, -halfWidth,
            stripLength, halfWidth
    };

    float sinOmega;
    float cosOmega;
    computeSinCos(omegaRotation, sinOmega, cosOmega);

    float sinWorld;
    float cosWorld;
    computeSinCos(angle + stateRotation, sinWorld, cosWorld);

    for (int i = 0; i < 4; i++) {
        const float localX = localVertices[i * 2];
        const float localY = localVertices[i * 2 + 1];
        const float omegaX = (localX * cosOmega - localY * sinOmega) * 0.9f;
        const float omegaY = localX * sinOmega + localY * cosOmega;

        verticesOut[i * 2] = posX + omegaX * cosWorld - omegaY * sinWorld;
        verticesOut[i * 2 + 1] = posY + omegaX * sinWorld + omegaY * cosWorld;
    }
}

inline void computeTexturedStripOuterVerticesScalar(const float startX, const float startY,
                                                    const float endX, const float endY,
                                                    const float startWidth, const float endWidth,
                                                    StripOuterVertices& outer) {
    const float dx = endX - startX;
    const float dy = endY - startY;
    const float length = std::sqrt(dx * dx + dy * dy);
    const float invLength = length == 0.0f ? 0.0f : 1.0f / length;
    const float normalX = dy * invLength;
    const float normalY = -dx * invLength;
    const float startOffsetX = normalX * startWidth * 0.5f;
    const float startOffsetY = normalY * startWidth * 0.5f;
    const float endOffsetX = normalX * endWidth * 0.5f;
    const float endOffsetY = normalY * endWidth * 0.5f;

    outer.x[0] = startX - startOffsetX;
    outer.y[0] = startY - startOffsetY;
    outer.x[1] = startX + startOffsetX;
    outer.y[1] = startY + startOffsetY;
    outer.x[2] = endX + endOffsetX;
    outer.y[2] = endY + endOffsetY;
    outer.x[3] = endX - endOffsetX;
    outer.y[3] = endY - endOffsetY;
}

#if defined(__SSE2__) && (defined(__x86_64__) || defined(_M_X64) || defined(__i386) || defined(_M_IX86))
inline void computeTexturedStripOuterVerticesSimd(const float startX, const float startY,
                                                  const float endX, const float endY,
                                                  const float startWidth, const float endWidth,
                                                  StripOuterVertices& outer) {
    const float dx = endX - startX;
    const float dy = endY - startY;
    const float length = std::sqrt(dx * dx + dy * dy);
    const float invLength = length == 0.0f ? 0.0f : 1.0f / length;
    const float normalX = dy * invLength;
    const float normalY = -dx * invLength;
    const float startOffsetX = normalX * startWidth * 0.5f;
    const float startOffsetY = normalY * startWidth * 0.5f;
    const float endOffsetX = normalX * endWidth * 0.5f;
    const float endOffsetY = normalY * endWidth * 0.5f;

    const __m128 baseX = _mm_setr_ps(startX, startX, endX, endX);
    const __m128 baseY = _mm_setr_ps(startY, startY, endY, endY);
    const __m128 offsetX = _mm_setr_ps(-startOffsetX, startOffsetX, endOffsetX, -endOffsetX);
    const __m128 offsetY = _mm_setr_ps(-startOffsetY, startOffsetY, endOffsetY, -endOffsetY);

    _mm_storeu_ps(outer.x, _mm_add_ps(baseX, offsetX));
    _mm_storeu_ps(outer.y, _mm_add_ps(baseY, offsetY));
}
#endif

inline void computeTexturedStripOuterVertices(const float startX, const float startY,
                                              const float endX, const float endY,
                                              const float startWidth, const float endWidth,
                                              StripOuterVertices& outer) {
#if defined(__SSE2__) && (defined(__x86_64__) || defined(_M_X64) || defined(__i386) || defined(_M_IX86))
    computeTexturedStripOuterVerticesSimd(startX, startY, endX, endY, startWidth, endWidth, outer);
#else
    computeTexturedStripOuterVerticesScalar(startX, startY, endX, endY, startWidth, endWidth, outer);
#endif
}

} // namespace ssoptimizer::render