package github.kasuminova.ssoptimizer.bridge.opengl;

/**
 * immediate 顶点流的回放目标：{@link VertexStream} 解码字节流后的调用接收方。
 * <p>
 * 动机：顶点流把 glBegin/glEnd 与 glVertex/glTexCoord/glColor/glNormal3f 族
 * 编码成字节流后，回放端需要一个落点接口——生产实现（{@link VertexArrayBatch}）
 * 把整段流合并成顶点数组后按图元段以 {@code glDrawArrays} 提交到真实
 * {@link org.lwjgl.opengl.GL11}；单测实现记录调用序列，
 * 从而在无 GL 上下文的环境下完整验证「编码 → 移交 → 解码」的往返正确性。
 * <p>
 * 方法集与 GL11 bridge 的流式录制族一一对应；语义与真实 GL 调用完全相同，
 * 仅把调用点从录制线程搬移到渲染线程。
 */
interface VertexSink {
    /**
     * 一段流的回放开始（{@link VertexStream#replay} 解码循环前的回调）：
     * 有跨批次累积状态的实现（如顶点数组合并器）在此重置。
     */
    default void startReplay() {
    }

    /**
     * 一段流的回放结束（{@link VertexStream#replay} 解码循环后的回调）：
     * 有挂起数据的实现（如未收口的图元段）在此收口，保证流内全部内容落地。
     */
    default void finishReplay() {
    }

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

    /**
     * 对应 {@code glEnable(cap)}（流内状态指令：在 glBegin/glEnd 段外执行）。
     * 由 sprite 渲染路径把状态设置编码进顶点流，避免每 sprite 一条非流式
     * 状态命令打断流段合并（v49 profile：主线程 flushVertexStream 热点）。
     */
    void enable(int cap);

    /** 对应 {@code glDisable(cap)}（流内状态指令，段外执行）。 */
    void disable(int cap);

    /** 对应 {@code glBlendFunc(src, dst)}（流内状态指令，段外执行）。 */
    void blendFunc(int src, int dst);

    /** 对应 {@code glBindTexture(TEXTURE_2D, texture)}（流内状态指令，段间执行）。 */
    void bindTexture(int texture);

    /**
     * 对应 {@code glPushMatrix()}（流内矩阵指令，段外执行）。
     * 默认实现抛出异常：矩阵指令只会流向两个生产 sink
     * （{@link VertexArrayBatch} / {@link ImmediateVertexSink}，均已实现）；
     * 测试记录桩等不消费矩阵指令的 sink 遇到本指令说明编码侧出现了预期外的
     * 指令流，必须当场暴露而非静默丢弃（丢弃会把矩阵变换吞掉、画面静默错位）。
     */
    default void pushMatrix() {
        throw new UnsupportedOperationException("sink 不支持流内矩阵指令 pushMatrix");
    }

    /** 对应 {@code glPopMatrix()}（流内矩阵指令，段外执行），默认实现语义同 {@link #pushMatrix()}。 */
    default void popMatrix() {
        throw new UnsupportedOperationException("sink 不支持流内矩阵指令 popMatrix");
    }

    /** 对应 {@code glLoadIdentity()}（流内矩阵指令，段外执行），默认实现语义同 {@link #pushMatrix()}。 */
    default void loadIdentity() {
        throw new UnsupportedOperationException("sink 不支持流内矩阵指令 loadIdentity");
    }

    /** 对应 {@code glTranslatef(x, y, z)}（流内矩阵指令，段外执行），默认实现语义同 {@link #pushMatrix()}。 */
    default void translatef(float x, float y, float z) {
        throw new UnsupportedOperationException("sink 不支持流内矩阵指令 translatef");
    }

    /** 对应 {@code glRotatef(angle, x, y, z)}（流内矩阵指令，段外执行），默认实现语义同 {@link #pushMatrix()}。 */
    default void rotatef(float angle, float x, float y, float z) {
        throw new UnsupportedOperationException("sink 不支持流内矩阵指令 rotatef");
    }

    /** 对应 {@code glScalef(x, y, z)}（流内矩阵指令，段外执行），默认实现语义同 {@link #pushMatrix()}。 */
    default void scalef(float x, float y, float z) {
        throw new UnsupportedOperationException("sink 不支持流内矩阵指令 scalef");
    }

    /** 对应 {@code glMatrixMode(mode)}（流内矩阵指令，段外执行），默认实现语义同 {@link #pushMatrix()}。 */
    default void matrixMode(int mode) {
        throw new UnsupportedOperationException("sink 不支持流内矩阵指令 matrixMode");
    }

    /**
     * 精灵四边形单操作码（begin(QUADS) + 4×(texCoord+vertex) + end 的融合形态）：
     * sprite 渲染路径（{@code SpriteRenderHelper}）把整组调用压成一条流指令，
     * 编码侧省去 13 次流调用，解码侧直写 4 个顶点进数组。
     * <p>
     * 默认实现按原语展开为等价调用序列（测试记录桩等无需感知本指令）；
     * tex 角点顺序：(texX,texY) (texX,texY+texH) (texX+texW,texY+texH) (texX+texW,texY)，
     * 与 {@code SpriteRenderHelper.fallbackRenderSprite} 的原始逐顶点序列一致。
     */
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
