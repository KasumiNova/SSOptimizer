package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ARBRobustness {
    private ARBRobustness() {
    }

    public static int glGetGraphicsResetStatusARB() {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBRobustness.glGetGraphicsResetStatusARB());
    }

    public static void glGetnColorTableARB(int p0, int p1, int p2, java.nio.ByteBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillBooleans(p3, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnColorTableARB(p0, p1, p2, buf)));
    }

    public static void glGetnColorTableARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillDoubles(p3, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnColorTableARB(p0, p1, p2, buf)));
    }

    public static void glGetnColorTableARB(int p0, int p1, int p2, java.nio.FloatBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p3, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnColorTableARB(p0, p1, p2, buf)));
    }

    public static void glGetnCompressedTexImageARB(int p0, int p1, java.nio.ByteBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillBooleans(p2, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnCompressedTexImageARB(p0, p1, buf)));
    }

    public static void glGetnCompressedTexImageARB(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnCompressedTexImageARB(p0, p1, buf)));
    }

    public static void glGetnCompressedTexImageARB(int p0, int p1, java.nio.ShortBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnCompressedTexImageARB(p0, p1, p2));
    }

    public static void glGetnCompressedTexImageARB(int p0, int p1, int p2, long p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBRobustness.glGetnCompressedTexImageARB(p0, p1, p2, p3));
    }

    public static void glGetnConvolutionFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillBooleans(p3, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnConvolutionFilterARB(p0, p1, p2, buf)));
    }

    public static void glGetnConvolutionFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillDoubles(p3, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnConvolutionFilterARB(p0, p1, p2, buf)));
    }

    public static void glGetnConvolutionFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p3, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnConvolutionFilterARB(p0, p1, p2, buf)));
    }

    public static void glGetnConvolutionFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p3, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnConvolutionFilterARB(p0, p1, p2, buf)));
    }

    public static void glGetnConvolutionFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnConvolutionFilterARB(p0, p1, p2, p3));
    }

    public static void glGetnConvolutionFilterARB(int p0, int p1, int p2, int p3, long p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBRobustness.glGetnConvolutionFilterARB(p0, p1, p2, p3, p4));
    }

    public static void glGetnHistogramARB(int p0, boolean p1, int p2, int p3, java.nio.ByteBuffer p4) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillBooleans(p4, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnHistogramARB(p0, p1, p2, p3, buf)));
    }

    public static void glGetnHistogramARB(int p0, boolean p1, int p2, int p3, java.nio.DoubleBuffer p4) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillDoubles(p4, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnHistogramARB(p0, p1, p2, p3, buf)));
    }

    public static void glGetnHistogramARB(int p0, boolean p1, int p2, int p3, java.nio.FloatBuffer p4) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p4, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnHistogramARB(p0, p1, p2, p3, buf)));
    }

    public static void glGetnHistogramARB(int p0, boolean p1, int p2, int p3, java.nio.IntBuffer p4) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p4, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnHistogramARB(p0, p1, p2, p3, buf)));
    }

    public static void glGetnHistogramARB(int p0, boolean p1, int p2, int p3, java.nio.ShortBuffer p4) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnHistogramARB(p0, p1, p2, p3, p4));
    }

    public static void glGetnHistogramARB(int p0, boolean p1, int p2, int p3, int p4, long p5) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBRobustness.glGetnHistogramARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnMapdvARB(int p0, int p1, java.nio.DoubleBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillDoubles(p2, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnMapdvARB(p0, p1, buf)));
    }

    public static void glGetnMapfvARB(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p2, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnMapfvARB(p0, p1, buf)));
    }

    public static void glGetnMapivARB(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnMapivARB(p0, p1, buf)));
    }

    public static void glGetnMinmaxARB(int p0, boolean p1, int p2, int p3, java.nio.ByteBuffer p4) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillBooleans(p4, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnMinmaxARB(p0, p1, p2, p3, buf)));
    }

    public static void glGetnMinmaxARB(int p0, boolean p1, int p2, int p3, java.nio.DoubleBuffer p4) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillDoubles(p4, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnMinmaxARB(p0, p1, p2, p3, buf)));
    }

    public static void glGetnMinmaxARB(int p0, boolean p1, int p2, int p3, java.nio.FloatBuffer p4) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p4, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnMinmaxARB(p0, p1, p2, p3, buf)));
    }

    public static void glGetnMinmaxARB(int p0, boolean p1, int p2, int p3, java.nio.IntBuffer p4) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p4, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnMinmaxARB(p0, p1, p2, p3, buf)));
    }

    public static void glGetnMinmaxARB(int p0, boolean p1, int p2, int p3, java.nio.ShortBuffer p4) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnMinmaxARB(p0, p1, p2, p3, p4));
    }

    public static void glGetnMinmaxARB(int p0, boolean p1, int p2, int p3, int p4, long p5) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBRobustness.glGetnMinmaxARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnPixelMapfvARB(int p0, java.nio.FloatBuffer p1) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p1, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnPixelMapfvARB(p0, buf)));
    }

    public static void glGetnPixelMapuivARB(int p0, java.nio.IntBuffer p1) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p1, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnPixelMapuivARB(p0, buf)));
    }

    public static void glGetnPixelMapusvARB(int p0, java.nio.ShortBuffer p1) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnPixelMapusvARB(p0, p1));
    }

    public static void glGetnPolygonStippleARB(java.nio.ByteBuffer p0) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillBooleans(p0, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnPolygonStippleARB(buf)));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.ByteBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.ByteBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.ByteBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.ByteBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.ByteBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.DoubleBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.DoubleBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.DoubleBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.DoubleBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.DoubleBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.FloatBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.FloatBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.FloatBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.FloatBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.FloatBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.IntBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.IntBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.IntBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.IntBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.IntBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.ShortBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.ShortBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.ShortBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.ShortBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ByteBuffer p3, java.nio.ShortBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.ByteBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.ByteBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.ByteBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.ByteBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.ByteBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.DoubleBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.DoubleBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.DoubleBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.DoubleBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.DoubleBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.FloatBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.FloatBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.FloatBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.FloatBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.FloatBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.IntBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.IntBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.IntBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.IntBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.IntBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.ShortBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.ShortBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.ShortBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.ShortBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.DoubleBuffer p3, java.nio.ShortBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.ByteBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.ByteBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.ByteBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.ByteBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.ByteBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.DoubleBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.DoubleBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.DoubleBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.DoubleBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.DoubleBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.FloatBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.FloatBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.FloatBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.FloatBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.FloatBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.IntBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.IntBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.IntBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.IntBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.IntBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.ShortBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.ShortBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.ShortBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.ShortBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.FloatBuffer p3, java.nio.ShortBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.ByteBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.ByteBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.ByteBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.ByteBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.ByteBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.DoubleBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.DoubleBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.DoubleBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.DoubleBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.DoubleBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.FloatBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.FloatBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.FloatBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.FloatBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.FloatBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.IntBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.IntBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.IntBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.IntBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.IntBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.ShortBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.ShortBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.ShortBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.ShortBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.ShortBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.ByteBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.ByteBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.ByteBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.ByteBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.ByteBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.DoubleBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.DoubleBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.DoubleBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.DoubleBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.DoubleBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.FloatBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.FloatBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.FloatBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.FloatBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.FloatBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.IntBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.IntBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.IntBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.IntBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.IntBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.ShortBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.ShortBuffer p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.ShortBuffer p4, java.nio.FloatBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.ShortBuffer p4, java.nio.IntBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, java.nio.ShortBuffer p3, java.nio.ShortBuffer p4, java.nio.ShortBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnSeparableFilterARB(int p0, int p1, int p2, int p3, long p4, int p5, long p6, long p7) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBRobustness.glGetnSeparableFilterARB(p0, p1, p2, p3, p4, p5, p6, p7));
    }

    public static void glGetnTexImageARB(int p0, int p1, int p2, int p3, java.nio.ByteBuffer p4) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillBooleans(p4, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnTexImageARB(p0, p1, p2, p3, buf)));
    }

    public static void glGetnTexImageARB(int p0, int p1, int p2, int p3, java.nio.DoubleBuffer p4) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillDoubles(p4, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnTexImageARB(p0, p1, p2, p3, buf)));
    }

    public static void glGetnTexImageARB(int p0, int p1, int p2, int p3, java.nio.FloatBuffer p4) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p4, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnTexImageARB(p0, p1, p2, p3, buf)));
    }

    public static void glGetnTexImageARB(int p0, int p1, int p2, int p3, java.nio.IntBuffer p4) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p4, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnTexImageARB(p0, p1, p2, p3, buf)));
    }

    public static void glGetnTexImageARB(int p0, int p1, int p2, int p3, java.nio.ShortBuffer p4) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBRobustness.glGetnTexImageARB(p0, p1, p2, p3, p4));
    }

    public static void glGetnTexImageARB(int p0, int p1, int p2, int p3, int p4, long p5) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBRobustness.glGetnTexImageARB(p0, p1, p2, p3, p4, p5));
    }

    public static void glGetnUniformdvARB(int p0, int p1, java.nio.DoubleBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillDoubles(p2, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnUniformdvARB(p0, p1, buf)));
    }

    public static void glGetnUniformfvARB(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p2, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnUniformfvARB(p0, p1, buf)));
    }

    public static void glGetnUniformivARB(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnUniformivARB(p0, p1, buf)));
    }

    public static void glGetnUniformuivARB(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.ARBRobustness.glGetnUniformuivARB(p0, p1, buf)));
    }

    public static void glReadnPixelsARB(int p0, int p1, int p2, int p3, int p4, int p5, java.nio.ByteBuffer p6) {
        BridgeSupport.enqueueSnapshot(p6, snapshot -> org.lwjgl.opengl.ARBRobustness.glReadnPixelsARB(p0, p1, p2, p3, p4, p5, snapshot));
    }

    public static void glReadnPixelsARB(int p0, int p1, int p2, int p3, int p4, int p5, java.nio.DoubleBuffer p6) {
        BridgeSupport.enqueueSnapshot(p6, snapshot -> org.lwjgl.opengl.ARBRobustness.glReadnPixelsARB(p0, p1, p2, p3, p4, p5, snapshot.asDoubleBuffer()));
    }

    public static void glReadnPixelsARB(int p0, int p1, int p2, int p3, int p4, int p5, java.nio.FloatBuffer p6) {
        BridgeSupport.enqueueSnapshot(p6, snapshot -> org.lwjgl.opengl.ARBRobustness.glReadnPixelsARB(p0, p1, p2, p3, p4, p5, snapshot.asFloatBuffer()));
    }

    public static void glReadnPixelsARB(int p0, int p1, int p2, int p3, int p4, int p5, java.nio.IntBuffer p6) {
        BridgeSupport.enqueueSnapshot(p6, snapshot -> org.lwjgl.opengl.ARBRobustness.glReadnPixelsARB(p0, p1, p2, p3, p4, p5, snapshot.asIntBuffer()));
    }

    public static void glReadnPixelsARB(int p0, int p1, int p2, int p3, int p4, int p5, java.nio.ShortBuffer p6) {
        BridgeSupport.enqueueSnapshot(p6, snapshot -> org.lwjgl.opengl.ARBRobustness.glReadnPixelsARB(p0, p1, p2, p3, p4, p5, snapshot.asShortBuffer()));
    }

    public static void glReadnPixelsARB(int p0, int p1, int p2, int p3, int p4, int p5, int p6, long p7) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBRobustness.glReadnPixelsARB(p0, p1, p2, p3, p4, p5, p6, p7));
    }
}
