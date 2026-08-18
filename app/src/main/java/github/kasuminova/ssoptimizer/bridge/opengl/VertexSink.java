package github.kasuminova.ssoptimizer.bridge.opengl;

/**
 * immediate 顶点流的回放目标：{@link VertexStream} 解码字节流后的调用接收方。
 * <p>
 * 动机：顶点流把 glBegin/glEnd 与 glVertex/glTexCoord/glColor/glNormal3f 族
 * 编码成字节流后，回放端需要一个落点接口——生产实现（{@link LwjglVertexSink}）
 * 逐条转发到真实 {@link org.lwjgl.opengl.GL11}；单测实现记录调用序列，
 * 从而在无 GL 上下文的环境下完整验证「编码 → 移交 → 解码」的往返正确性。
 * <p>
 * 方法集与 GL11 bridge 的流式录制族一一对应；语义与真实 GL 调用完全相同，
 * 仅把调用点从录制线程搬移到渲染线程。
 */
interface VertexSink {
    /** 对应 {@code glBegin(mode)}。 */
    void begin(int mode);

    /** 对应 {@code glEnd()}。 */
    void end();

    /** 对应 {@code glVertex2f(x, y)}。 */
    void vertex2f(float x, float y);

    /** 对应 {@code glVertex3f(x, y, z)}。 */
    void vertex3f(float x, float y, float z);

    /** 对应 {@code glVertex2d(x, y)}。 */
    void vertex2d(double x, double y);

    /** 对应 {@code glVertex3d(x, y, z)}。 */
    void vertex3d(double x, double y, double z);

    /** 对应 {@code glTexCoord2f(s, t)}。 */
    void texCoord2f(float s, float t);

    /** 对应 {@code glTexCoord2d(s, t)}。 */
    void texCoord2d(double s, double t);

    /** 对应 {@code glColor4ub(r, g, b, a)}（字节按原样传递，含负值）。 */
    void color4ub(byte red, byte green, byte blue, byte alpha);

    /** 对应 {@code glColor3ub(r, g, b)}。 */
    void color3ub(byte red, byte green, byte blue);

    /** 对应 {@code glColor3f(r, g, b)}。 */
    void color3f(float red, float green, float blue);

    /** 对应 {@code glColor4f(r, g, b, a)}。 */
    void color4f(float red, float green, float blue, float alpha);

    /** 对应 {@code glColor3d(r, g, b)}。 */
    void color3d(double red, double green, double blue);

    /** 对应 {@code glNormal3f(nx, ny, nz)}。 */
    void normal3f(float nx, float ny, float nz);
}
