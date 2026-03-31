package github.kasuminova.ssoptimizer.loading;

import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

/**
 * Ensures tightly-packed Java ByteBuffer texture uploads use a compatible
 * unpack state when NPOT textures are enabled.
 */
public final class TextureUploadHelper {
    private static final int GL_UNPACK_ROW_LENGTH  = 3314;
    private static final int GL_UNPACK_SKIP_ROWS   = 3315;
    private static final int GL_UNPACK_SKIP_PIXELS = 3316;
    private static final int GL_UNPACK_ALIGNMENT   = 3317;

    private TextureUploadHelper() {
    }

    public static void glTexImage2D(final int target,
                                    final int level,
                                    final int internalFormat,
                                    final int width,
                                    final int height,
                                    final int border,
                                    final int format,
                                    final int type,
                                    final ByteBuffer pixels) {
        prepareUnpackState();
        try {
            GL11.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
        } finally {
            restoreUnpackState();
        }
    }

    public static void glTexSubImage2D(final int target,
                                       final int level,
                                       final int xOffset,
                                       final int yOffset,
                                       final int width,
                                       final int height,
                                       final int format,
                                       final int type,
                                       final ByteBuffer pixels) {
        prepareUnpackState();
        try {
            GL11.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
        } finally {
            restoreUnpackState();
        }
    }

    private static void prepareUnpackState() {
        GL11.glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    }

    private static void restoreUnpackState() {
        GL11.glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    }
}