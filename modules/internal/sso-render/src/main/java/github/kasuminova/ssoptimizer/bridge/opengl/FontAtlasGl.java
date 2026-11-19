package github.kasuminova.ssoptimizer.bridge.opengl;

import java.nio.ByteBuffer;
import java.util.List;

/** 仅在持有 GL 上下文的线程调用。此包排除 GL 字节码改写。 */
public final class FontAtlasGl {

    private FontAtlasGl() {
    }

    private static int unbindUnpackBuffer() {
        final var caps = org.lwjgl.opengl.GLContext.getCapabilities();
        if (!caps.OpenGL21 && !caps.GL_ARB_pixel_buffer_object && !caps.GL_EXT_pixel_buffer_object) return 0;
        final int buffer = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        if (buffer != 0) bindUnpackBuffer(0);
        return buffer;
    }

    private static void bindUnpackBuffer(final int buffer) {
        if (org.lwjgl.opengl.GLContext.getCapabilities().OpenGL15) {
            org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, buffer);
        } else {
            org.lwjgl.opengl.ARBBufferObject.glBindBufferARB(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, buffer);
        }
    }

    public static int createAlphaTexture(final int size) {
        final int previousBinding = org.lwjgl.opengl.GL11.glGetInteger(
                org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        final int unpackBuffer = unbindUnpackBuffer();
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
            if (unpackBuffer != 0) bindUnpackBuffer(unpackBuffer);
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
        withUnpackState(textureId, pageSize, () -> {
            for (int i = 0; i < rects.size(); i++) {
                final int[] rect = rects.get(i);
                uploadRect(pageSize, rect[0], rect[1], rect[2], rect[3], payloads.get(i));
            }
        });
    }

    public static void uploadAlphaRect(final int textureId, final int pageSize, final int x, final int y,
                                       final int width, final int height, final ByteBuffer packed) {
        if (!packed.isDirect() || (long) width * height != packed.remaining()) {
            throw new IllegalArgumentException("字形上传需要与矩形尺寸一致的直接缓冲区");
        }
        withUnpackState(textureId, 0, () -> uploadRect(pageSize, x, y, width, height, packed));
    }

    private static void uploadRect(final int pageSize, final int x, final int y, final int width,
                                   final int height, final ByteBuffer payload) {
        if (x < 0 || y < 0 || width <= 0 || height <= 0 || width > pageSize - x || height > pageSize - y) {
            throw new IllegalStateException("图集脏矩形越界: rect=" + x + "," + y + "," + width + "," + height
                    + " pageSize=" + pageSize);
        }
        org.lwjgl.opengl.GL11.glTexSubImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, x, y, width, height,
                org.lwjgl.opengl.GL11.GL_ALPHA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, payload);
    }

    private static void withUnpackState(final int textureId, final int rowLength, final Runnable upload) {
        final int previousBinding = org.lwjgl.opengl.GL11.glGetInteger(
                org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        final int previousAlignment = org.lwjgl.opengl.GL11.glGetInteger(
                org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT);
        final int previousRowLength = org.lwjgl.opengl.GL11.glGetInteger(
                org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH);
        final int previousSkipRows = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS);
        final int previousSkipPixels = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS);
        final int unpackBuffer = unbindUnpackBuffer();
        try {
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, textureId);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, rowLength);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS, 0);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS, 0);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT, 1);
            upload.run();
        } finally {
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, previousBinding);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT, previousAlignment);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, previousRowLength);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS, previousSkipRows);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS, previousSkipPixels);
            if (unpackBuffer != 0) bindUnpackBuffer(unpackBuffer);
        }
    }

    public static void deleteTexture(final int textureId) {
        if (textureId != 0) {
            org.lwjgl.opengl.GL11.glDeleteTextures(textureId);
        }
    }
}
