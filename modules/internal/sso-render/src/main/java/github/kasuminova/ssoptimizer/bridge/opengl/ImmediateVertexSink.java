package github.kasuminova.ssoptimizer.bridge.opengl;

/** 仅在渲染线程回放跨批次未收口的 immediate 图元段。 */
enum ImmediateVertexSink implements VertexSink {
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

    @Override
    public void pushMatrix() {
        org.lwjgl.opengl.GL11.glPushMatrix();
    }

    @Override
    public void popMatrix() {
        org.lwjgl.opengl.GL11.glPopMatrix();
    }

    @Override
    public void loadIdentity() {
        org.lwjgl.opengl.GL11.glLoadIdentity();
    }

    @Override
    public void translatef(float x, float y, float z) {
        org.lwjgl.opengl.GL11.glTranslatef(x, y, z);
    }

    @Override
    public void rotatef(float angle, float x, float y, float z) {
        org.lwjgl.opengl.GL11.glRotatef(angle, x, y, z);
    }

    @Override
    public void scalef(float x, float y, float z) {
        org.lwjgl.opengl.GL11.glScalef(x, y, z);
    }

    @Override
    public void matrixMode(int mode) {
        org.lwjgl.opengl.GL11.glMatrixMode(mode);
    }
}
