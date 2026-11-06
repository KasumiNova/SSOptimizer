package github.kasuminova.ssoptimizer.bridge.opengl;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * 在渲染线程直接执行字形图集 GL 操作。
 * 本类必须留在 bridge 改写排除包内，避免真实 GL 调用被再次录制。
 * 所有方法仅限渲染线程调用；纹理绑定、UNPACK_ALIGNMENT 与 UNPACK_ROW_LENGTH 在返回前恢复。
 */
public final class FontAtlasGl {

    private FontAtlasGl() {
    }

    public static int createAlphaTexture(final int size) {
        final int previousBinding = org.lwjgl.opengl.GL11.glGetInteger(
                org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        try {
            final int id = org.lwjgl.opengl.GL11.glGenTextures();
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, id);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            org.lwjgl.opengl.GL11.glTexImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                    org.lwjgl.opengl.GL11.GL_ALPHA8, size, size, 0,
                    org.lwjgl.opengl.GL11.GL_ALPHA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
                    (ByteBuffer) null);
            return id;
        } finally {
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, previousBinding);
        }
    }

    /** payloads 必须是指向矩形原点的页内视图，并在命令执行期间保持底层内存有效。 */
    public static void uploadAlphaRects(final int textureId,
                                        final int pageSize,
                                        final List<int[]> rects,
                                        final List<ByteBuffer> payloads) {
        if (rects.size() != payloads.size()) {
            throw new IllegalStateException("[SSOptimizer] 图集上传 rects/payloads 不等长: "
                    + rects.size() + "/" + payloads.size());
        }
        final int previousBinding = org.lwjgl.opengl.GL11.glGetInteger(
                org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        final int previousAlignment = org.lwjgl.opengl.GL11.glGetInteger(
                org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT);
        final int previousRowLength = org.lwjgl.opengl.GL11.glGetInteger(
                org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH);
        try {
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, textureId);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, pageSize);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS, 0);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS, 0);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT, 1);
            for (int i = 0; i < rects.size(); i++) {
                final int[] rect = rects.get(i);
                if (rect[0] < 0 || rect[1] < 0 || rect[2] <= 0 || rect[3] <= 0
                        || rect[0] + rect[2] > pageSize || rect[1] + rect[3] > pageSize) {
                    throw new IllegalStateException("[SSOptimizer] 图集脏矩形越界: rect="
                            + rect[0] + "," + rect[1] + " " + rect[2] + "x" + rect[3]
                            + " pageSize=" + pageSize);
                }
                org.lwjgl.opengl.GL11.glTexSubImage2D(
                        org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                        rect[0], rect[1], rect[2], rect[3],
                        org.lwjgl.opengl.GL11.GL_ALPHA,
                        org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
                        payloads.get(i));
            }
        } finally {
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, previousBinding);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT, previousAlignment);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, previousRowLength);
        }
    }

    public static void deleteTexture(final int textureId) {
        if (textureId != 0) {
            org.lwjgl.opengl.GL11.glDeleteTextures(textureId);
        }
    }
}
