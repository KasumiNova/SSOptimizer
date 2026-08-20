package github.kasuminova.ssoptimizer.bridge.opengl;

/**
 * {@link VertexSink} 的逐指令 immediate 回放实现：仅用于「开放段跨批次切割」的
 * 病态批次（glBegin 与配对的 glEnd 之间被非流式命令切开，典型如 begin..end 段内
 * 的 glCallList——显示列表消费<b>调用时刻</b>的 current color 做纹理调制）。
 * <p>
 * 该路径下数组化回放（{@link VertexArrayBatch}）无法保持语义：顶点数组要求完整
 * 图元段，而真实 GL 的 begin 必须保持开放跨命令执行；current 值也必须逐指令推进。
 * 因此这类批次退回逐条真实 {@link org.lwjgl.opengl.GL11} 调用（与数组化前的
 * 回放行为逐指令等价）。正常批次（段完整）永远走 {@link VertexArrayBatch}，
 * 本类只在罕见病态序列出现时使用，性能不敏感。
 * <p>
 * 只在渲染线程的流段回放命令里被调用（此时 GL 上下文归渲染线程持有）。
 */
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
}
