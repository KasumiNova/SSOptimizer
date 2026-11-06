package github.kasuminova.ssoptimizer.bridge.opengl;

interface VertexSink {
    default void startReplay() {
    }

    default void finishReplay() {
    }

    void begin(int mode);

    void end();

    void vertex2f(float x, float y);

    void vertex3f(float x, float y, float z);

    void vertex2d(double x, double y);

    void vertex3d(double x, double y, double z);

    void texCoord2f(float s, float t);

    void texCoord2d(double s, double t);

    void color4ub(byte red, byte green, byte blue, byte alpha);

    void color3ub(byte red, byte green, byte blue);

    void color3f(float red, float green, float blue);

    void color4f(float red, float green, float blue, float alpha);

    void color3d(double red, double green, double blue);

    void normal3f(float nx, float ny, float nz);

    void enable(int cap);

    void disable(int cap);

    void blendFunc(int src, int dst);

    void bindTexture(int texture);

    /** 默认拒绝矩阵指令，避免不支持该指令的接收方静默丢弃变换。 */
    default void pushMatrix() {
        throw new UnsupportedOperationException("sink 不支持流内矩阵指令 pushMatrix");
    }

    default void popMatrix() {
        throw new UnsupportedOperationException("sink 不支持流内矩阵指令 popMatrix");
    }

    default void loadIdentity() {
        throw new UnsupportedOperationException("sink 不支持流内矩阵指令 loadIdentity");
    }

    default void translatef(float x, float y, float z) {
        throw new UnsupportedOperationException("sink 不支持流内矩阵指令 translatef");
    }

    default void rotatef(float angle, float x, float y, float z) {
        throw new UnsupportedOperationException("sink 不支持流内矩阵指令 rotatef");
    }

    default void scalef(float x, float y, float z) {
        throw new UnsupportedOperationException("sink 不支持流内矩阵指令 scalef");
    }

    default void matrixMode(int mode) {
        throw new UnsupportedOperationException("sink 不支持流内矩阵指令 matrixMode");
    }

    default void spriteQuad(
            float x0, float y0, float x1, float y1,
            float x2, float y2, float x3, float y3,
            float texX, float texY, float texWidth, float texHeight) {
        begin(org.lwjgl.opengl.GL11.GL_QUADS);
        texCoord2f(texX, texY);
        vertex2f(x0, y0);
        texCoord2f(texX, texY + texHeight);
        vertex2f(x1, y1);
        texCoord2f(texX + texWidth, texY + texHeight);
        vertex2f(x2, y2);
        texCoord2f(texX + texWidth, texY);
        vertex2f(x3, y3);
        end();
    }
}
