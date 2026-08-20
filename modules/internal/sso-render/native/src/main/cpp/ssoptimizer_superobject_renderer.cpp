#include "github_kasuminova_ssoptimizer_common_render_engine_BitmapFontRendererHelper.h"
#include "ssoptimizer_render_common.h"

using namespace ssoptimizer::render;

extern "C" {

JNIEXPORT void JNICALL Java_github_kasuminova_ssoptimizer_common_render_engine_BitmapFontRendererHelper_nativeRenderGlyphQuad(
        JNIEnv*, jclass,
        jfloat x, jfloat y,
        jint glyphWidth, jint glyphHeight, jint bearingY,
        jfloat texX, jfloat texY, jfloat texWidth, jfloat texHeight,
        jfloat scale,
        jint shadowCopies,
    jfloat shadowScale) {

    const float glyphWidthF = static_cast<float>(glyphWidth);
    const float glyphHeightF = static_cast<float>(glyphHeight);
    const float bearingYF = static_cast<float>(bearingY);

    if (shadowCopies > 0) {
        for (int shadowIndex = 1; shadowIndex <= shadowCopies; shadowIndex++) {
            const float shadowOffset = static_cast<float>(shadowIndex) * shadowScale;
            float widthOffset = shadowOffset;
            float heightOffset = shadowOffset;

            if (glyphWidthF > glyphHeightF && glyphWidthF != 0.0f) {
                heightOffset *= glyphHeightF / glyphWidthF;
            }
            if (glyphHeightF > glyphWidthF && glyphHeightF != 0.0f) {
                widthOffset *= glyphWidthF / glyphHeightF;
            }

            glTexCoord2f(texX, texY + texHeight);
            glVertex2f(x - widthOffset, y - scale * bearingYF - heightOffset);

            glTexCoord2f(texX, texY);
            glVertex2f(x - widthOffset,
                       y - scale * glyphHeightF - scale * bearingYF + heightOffset * 2.0f);

            glTexCoord2f(texX + texWidth, texY);
            glVertex2f(x + scale * glyphWidthF + widthOffset * 2.0f,
                       y - scale * glyphHeightF - scale * bearingYF + heightOffset * 2.0f);

            glTexCoord2f(texX + texWidth, texY + texHeight);
            glVertex2f(x + scale * glyphWidthF + widthOffset * 2.0f,
                       y - scale * bearingYF - heightOffset);
        }
    }

    glTexCoord2f(texX, texY + texHeight);
    glVertex2f(x, y - scale * bearingYF);

    glTexCoord2f(texX, texY);
    glVertex2f(x, y - scale * glyphHeightF - scale * bearingYF);

    glTexCoord2f(texX + texWidth, texY);
    glVertex2f(x + scale * glyphWidthF, y - scale * glyphHeightF - scale * bearingYF);

    glTexCoord2f(texX + texWidth, texY + texHeight);
    glVertex2f(x + scale * glyphWidthF, y - scale * bearingYF);
}

} // extern "C"