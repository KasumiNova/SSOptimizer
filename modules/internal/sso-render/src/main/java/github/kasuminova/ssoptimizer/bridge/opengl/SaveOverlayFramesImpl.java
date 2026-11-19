package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.api.render.SaveOverlayFrames;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import java.util.Objects;

public final class SaveOverlayFramesImpl implements SaveOverlayFrames {
    private static final Logger LOGGER = Logger.getLogger(SaveOverlayFramesImpl.class);
    private static final boolean RENDER_THREAD_ENABLED = RenderThreadMode.isEnabled();

    private Object contextToken;
    private long capturedPresentation;
    private int framebuffer;
    private int renderbuffer;
    private int capturedWidth;
    private int capturedHeight;
    private int capturedSamples;
    private int capturedFormat;
    private boolean warnedUnsupported;
    private int failedWidth;
    private int failedHeight;

    @Override
    public boolean isProducer() {
        return RENDER_THREAD_ENABLED && BridgeSupport.installedQueue() != null && GlDispatch.isMainRecordingThread();
    }

    @Override
    public void restoreBackground(final Object dialog) {
        if (isProducer()) {
            Objects.requireNonNull(dialog, "dialog");
            GlDispatch.submit(this::restoreOnRenderThread);
        }
    }

    private void restoreOnRenderThread() {
        final Object token = Display.isCreated() ? BridgeSupport.mainGlContextToken() : null;
        if (contextToken != token) {
            contextToken = token;
            capturedPresentation = 0;
            framebuffer = renderbuffer = 0;
            capturedWidth = capturedHeight = 0;
            failedWidth = failedHeight = 0;
            warnedUnsupported = false;
        }
        if (token == null) {
            return;
        }
        final long normalPresentation = Display.markSaveOverlayFrame();
        if (normalPresentation == 0 && capturedPresentation == 0) {
            return;
        }

        final var caps = GLContext.getCapabilities();
        if (!(caps.OpenGL30 || caps.GL_ARB_framebuffer_object)) {
            warnUnsupported();
            return;
        }

        final float scale = Display.getPixelScaleFactor();
        final int width = Math.round(Display.getWidth() * scale);
        final int height = Math.round(Display.getHeight() * scale);
        if (width <= 0 || height <= 0) {
            return;
        }

        final int readBinding = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int drawBinding = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        final boolean srgb = (caps.OpenGL30 || caps.GL_ARB_framebuffer_sRGB || caps.GL_EXT_framebuffer_sRGB)
                && GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
        ARBFramebufferObject.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        ARBFramebufferObject.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
        final int readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        final int drawBuffer = GL11.glGetInteger(GL11.GL_DRAW_BUFFER);
        try {
            if (!GL11.glGetBoolean(GL11.GL_DOUBLEBUFFER)) {
                warnUnsupported();
                return;
            }
            if (scissor) {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
            if (srgb) {
                GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            }
            final int samples = GL11.glGetInteger(GL13.GL_SAMPLES);
            if (normalPresentation != 0 && normalPresentation != capturedPresentation) {
                final int format = defaultColorFormat();
                if (!prepareFramebuffer(width, height, samples, format)) {
                    return;
                }
                GL11.glReadBuffer(GL11.GL_FRONT);
                ARBFramebufferObject.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
                blit(width, height, width, height);
                capturedPresentation = normalPresentation;
            }
            if (capturedSamples > 0 && samples > 0 && capturedSamples != samples) {
                warnUnsupported();
                return;
            }

            ARBFramebufferObject.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
            ARBFramebufferObject.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            GL11.glDrawBuffer(GL11.GL_BACK);
            if (capturedSamples > 0 || samples > 0) {
                // 多重采样 blit 要求源与目标矩形同尺寸。
                final int copyWidth = Math.min(capturedWidth, width);
                final int copyHeight = Math.min(capturedHeight, height);
                blit(copyWidth, copyHeight, copyWidth, copyHeight);
            } else {
                blit(capturedWidth, capturedHeight, width, height);
            }
        } finally {
            ARBFramebufferObject.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
            GL11.glReadBuffer(readBuffer);
            ARBFramebufferObject.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            GL11.glDrawBuffer(drawBuffer);
            ARBFramebufferObject.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readBinding);
            ARBFramebufferObject.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawBinding);
            if (scissor) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            }
            if (srgb) {
                GL11.glEnable(GL30.GL_FRAMEBUFFER_SRGB);
            }
        }
    }

    private boolean prepareFramebuffer(final int width, final int height, final int samples, final int format) {
        if (framebuffer != 0 && capturedWidth == width && capturedHeight == height
                && capturedSamples == samples && capturedFormat == format) {
            return true;
        }
        if (failedWidth == width && failedHeight == height) {
            return false;
        }

        final int maxSize = GL11.glGetInteger(GL30.GL_MAX_RENDERBUFFER_SIZE);
        if (width > maxSize || height > maxSize) {
            recordAllocationFailure(width, height, "超过渲染缓冲尺寸上限 " + maxSize);
            return false;
        }

        final int previousRenderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        if (framebuffer == 0) {
            framebuffer = ARBFramebufferObject.glGenFramebuffers();
            renderbuffer = ARBFramebufferObject.glGenRenderbuffers();
        }
        capturedPresentation = 0;
        capturedWidth = capturedHeight = 0;
        try {
            ARBFramebufferObject.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer);
            ARBFramebufferObject.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, samples, format, width, height);
            if (ARBFramebufferObject.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_WIDTH) != width
                    || ARBFramebufferObject.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_HEIGHT) != height
                    || ARBFramebufferObject.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_SAMPLES) != samples) {
                recordAllocationFailure(width, height, "渲染缓冲尺寸或采样数与窗口不匹配");
                return false;
            }
            ARBFramebufferObject.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            ARBFramebufferObject.glFramebufferRenderbuffer(GL30.GL_DRAW_FRAMEBUFFER,
                    GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, renderbuffer);
            final int status = ARBFramebufferObject.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                recordAllocationFailure(width, height, "帧缓冲状态 0x" + Integer.toHexString(status));
                return false;
            }
            capturedWidth = width;
            capturedHeight = height;
            capturedSamples = samples;
            capturedFormat = format;
            failedWidth = failedHeight = 0;
            return true;
        } finally {
            ARBFramebufferObject.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previousRenderbuffer);
        }
    }

    private void warnUnsupported() {
        if (!warnedUnsupported) {
            LOGGER.warn("[SSOptimizer] 存档遮罩要求支持帧缓冲 blit 的双缓冲窗口");
            warnedUnsupported = true;
        }
    }

    private void recordAllocationFailure(final int width, final int height, final String reason) {
        failedWidth = width;
        failedHeight = height;
        LOGGER.warn("[SSOptimizer] 存档背景帧缓冲创建失败 (" + width + "x" + height + ")：" + reason);
    }

    private static int defaultColorFormat() {
        return GL11.glGetInteger(GL11.GL_ALPHA_BITS) == 0 ? GL11.GL_RGB8 : GL11.GL_RGBA8;
    }

    private static void blit(final int srcWidth, final int srcHeight, final int dstWidth, final int dstHeight) {
        ARBFramebufferObject.glBlitFramebuffer(0, 0, srcWidth, srcHeight, 0, 0, dstWidth, dstHeight,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
    }
}
