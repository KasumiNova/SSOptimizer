package github.kasuminova.ssoptimizer.bridge.opengl;

/**
 * {@link VertexSink} 的生产实现：逐条转发到真实 {@link org.lwjgl.opengl.GL11}。
 * 只在渲染线程的流段回放命令里被调用（此时 GL 上下文归渲染线程持有）。
 */
enum LwjglVertexSink implements VertexSink {
    INSTANCE;

    @Override
    public void begin(int mode) {
        org.lwjgl.opengl.GL11.glBegin(mode);
    }

    @Override
    public void end() {
        org.lwjgl.opengl.GL11.glEnd();
    }

    @Override
    public void vertex2f(float x, float y) {
        org.lwjgl.opengl.GL11.glVertex2f(x, y);
    }

    @Override
    public void vertex3f(float x, float y, float z) {
        org.lwjgl.opengl.GL11.glVertex3f(x, y, z);
    }

    @Override
    public void vertex2d(double x, double y) {
        org.lwjgl.opengl.GL11.glVertex2d(x, y);
    }

    @Override
    public void vertex3d(double x, double y, double z) {
        org.lwjgl.opengl.GL11.glVertex3d(x, y, z);
    }

    @Override
    public void texCoord2f(float s, float t) {
        org.lwjgl.opengl.GL11.glTexCoord2f(s, t);
    }

    @Override
    public void texCoord2d(double s, double t) {
        org.lwjgl.opengl.GL11.glTexCoord2d(s, t);
    }

    @Override
    public void color4ub(byte red, byte green, byte blue, byte alpha) {
        org.lwjgl.opengl.GL11.glColor4ub(red, green, blue, alpha);
    }

    @Override
    public void color3ub(byte red, byte green, byte blue) {
        org.lwjgl.opengl.GL11.glColor3ub(red, green, blue);
    }

    @Override
    public void color3f(float red, float green, float blue) {
        org.lwjgl.opengl.GL11.glColor3f(red, green, blue);
    }

    @Override
    public void color4f(float red, float green, float blue, float alpha) {
        org.lwjgl.opengl.GL11.glColor4f(red, green, blue, alpha);
    }

    @Override
    public void color3d(double red, double green, double blue) {
        org.lwjgl.opengl.GL11.glColor3d(red, green, blue);
    }

    @Override
    public void normal3f(float nx, float ny, float nz) {
        org.lwjgl.opengl.GL11.glNormal3f(nx, ny, nz);
    }

    @Override
    public void enable(int cap) {
        org.lwjgl.opengl.GL11.glEnable(cap);
    }

    @Override
    public void disable(int cap) {
        org.lwjgl.opengl.GL11.glDisable(cap);
    }

    @Override
    public void blendFunc(int src, int dst) {
        org.lwjgl.opengl.GL11.glBlendFunc(src, dst);
    }

    @Override
    public void bindTexture(int texture) {
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texture);
    }
}
