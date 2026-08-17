package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * org.lwjgl.opengl.GL11 的 bridge 镜像（游戏使用面全集）。
 * <p>
 * 动机：ASM 重定向阶段会把游戏/模组字节码中 INVOKESTATIC owner
 * {@code org/lwjgl/opengl/GL11} 改写到本类，使所有固定管线调用不再直接触碰 GL
 * 上下文，而是封成 {@code GlCommand} 入队到 {@link RenderQueue} 当前帧，由渲染
 * 线程执行。本类与 LWJGL 保持同签名静态方法，保证改写后调用点无需额外适配。
 * 覆盖面依据 docs/design/gl-call-inventory.md 第一节的盘点结论。
 * <p>
 * 本阶段（bridge + 单测）的语义约定：
 * <ul>
 *   <li>无参/标量参数命令：1:1 录制一条命令，命令体调真
 *       {@link org.lwjgl.opengl.GL11}；</li>
 *   <li>buffer 参数命令（glTexImage/glBufferData/glLoadMatrix 等）：录制时经
 *       {@link BridgeSupport#pool()} 深拷贝为池化快照，命令执行后归还，调用方
 *       录制后改写源 buffer 不影响命令；</li>
 *   <li>client pointer（glVertexPointer 等）：录制时刻快照进
 *       {@link ClientPointerState}，draw 命令（glDrawArrays/glDrawElements/
 *       glArrayElement）携带快照组在执行前全量重放——简化语义与 FR
 *       ClientAttribTracker 的差异见 {@link PointerSnapshot} 的 javadoc；</li>
 *   <li>getter（glGetInteger/glGetFloat/glGetBoolean/glGetString/glIsEnabled
 *       /glReadPixels/glGetError 等）与资源分配
 *       （glGenTextures/glGenLists）：走 {@link RenderQueue#get} 阻塞通道
 *       （自动计入 StallDetector）；后续演进点是把高频 pname/资源 id 换成
 *       主线程侧状态仿真与预生成 stash（见盘点文档 getter 清单）；</li>
 *   <li>display list（glNewList/glEndList/glCallList）：本阶段按普通命令入队
 *       ——即「渲染线程直接执行真实 display list 编译/调用」，语义等价于单线程，
 *       只是 display list 本体编译发生在渲染线程。ListManager 式命令帧重放
 *       （编译期展开为命令序列、跨帧复用）留待后续轮次。</li>
 * </ul>
 * 后续阶段计划：immediate 顶点流被顶点拦截器当场变换进批量缓冲（glEnd 转
 * draw 命令）；矩阵操作改走主线程 CPU 仿真栈不再产生 GL 命令；glFlush/glFinish
 * 评估抹成 no-op（语义由帧同步点统一保证）。
 * <p>
 * 注入：{@link #install(RenderQueue)} 装配命令消费者（游戏接入时由 bootstrap
 * 安装真实队列；单测安装假队列验证录制行为）。未安装时调用直接抛
 * {@link IllegalStateException}——桥接类没有可回退的直通路径。
 */
public final class GL11 {
    private GL11() {
    }

    /**
     * 安装命令消费者。游戏接入时由 bootstrap 在 ASM 重定向生效前调用。
     *
     * @param renderQueue 渲染队列实例
     */
    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    /** 测试用：卸载已安装的队列，避免用例间静态状态串扰。 */
    static void uninstall() {
        BridgeSupport.uninstall();
    }

    // ------------------------------------------------------------------
    // draw 助手：携带当前 client pointer 快照组的绘制命令
    // ------------------------------------------------------------------

    /** 依赖 client pointer 状态的绘制调用体。 */
    private interface DrawCall {
        void execute();
    }

    /**
     * 录制一条 draw 命令：渲染线程执行时先全量重放录制时刻捕获的 pointer
     * 快照组，再执行真实 draw；finally 中归还快照组。
     */
    private static void enqueueDraw(DrawCall draw) {
        PointerSnapshotGroup group = BridgeSupport.pointerState().capture();
        BridgeSupport.enqueue(() -> {
            try {
                group.apply();
                draw.execute();
            } finally {
                group.release();
            }
        });
    }

    // ------------------------------------------------------------------
    // immediate 顶点流
    // ------------------------------------------------------------------

    public static void glBegin(int mode) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glBegin(mode));
    }

    public static void glEnd() {
        BridgeSupport.enqueue(org.lwjgl.opengl.GL11::glEnd);
    }

    public static void glVertex2f(float x, float y) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glVertex2f(x, y));
    }

    public static void glVertex3f(float x, float y, float z) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glVertex3f(x, y, z));
    }

    public static void glVertex2d(double x, double y) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glVertex2d(x, y));
    }

    public static void glVertex3d(double x, double y, double z) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glVertex3d(x, y, z));
    }

    public static void glTexCoord2f(float s, float t) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTexCoord2f(s, t));
    }

    public static void glTexCoord2d(double s, double t) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTexCoord2d(s, t));
    }

    public static void glColor4ub(byte red, byte green, byte blue, byte alpha) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glColor4ub(red, green, blue, alpha));
    }

    public static void glColor3ub(byte red, byte green, byte blue) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glColor3ub(red, green, blue));
    }

    public static void glColor3f(float red, float green, float blue) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glColor3f(red, green, blue));
    }

    public static void glColor4f(float red, float green, float blue, float alpha) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glColor4f(red, green, blue, alpha));
    }

    public static void glColor3d(double red, double green, double blue) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glColor3d(red, green, blue));
    }

    public static void glNormal3f(float nx, float ny, float nz) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glNormal3f(nx, ny, nz));
    }

    public static void glEdgeFlag(boolean flag) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glEdgeFlag(flag));
    }

    /** immediate 矩形（独立于 glBegin/glEnd 的单命令图元）。 */
    public static void glRectf(float x1, float y1, float x2, float y2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glRectf(x1, y1, x2, y2));
    }

    public static void glRectd(double x1, double y1, double x2, double y2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glRectd(x1, y1, x2, y2));
    }

    /** 选择读取缓冲（glReadPixels 的配对状态）。 */
    public static void glReadBuffer(int mode) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glReadBuffer(mode));
    }

    /** 单元素绘制，同样携带 pointer 快照组（见 {@link #enqueueDraw}）。 */
    public static void glArrayElement(int index) {
        enqueueDraw(() -> org.lwjgl.opengl.GL11.glArrayElement(index));
    }

    // ------------------------------------------------------------------
    // 矩阵
    // ------------------------------------------------------------------

    public static void glMatrixMode(int mode) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glMatrixMode(mode));
    }

    public static void glLoadIdentity() {
        BridgeSupport.enqueue(org.lwjgl.opengl.GL11::glLoadIdentity);
    }

    public static void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glOrtho(left, right, bottom, top, zNear, zFar));
    }

    public static void glPushMatrix() {
        BridgeSupport.enqueue(org.lwjgl.opengl.GL11::glPushMatrix);
    }

    public static void glPopMatrix() {
        BridgeSupport.enqueue(org.lwjgl.opengl.GL11::glPopMatrix);
    }

    public static void glTranslatef(float x, float y, float z) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTranslatef(x, y, z));
    }

    public static void glTranslated(double x, double y, double z) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTranslated(x, y, z));
    }

    public static void glRotatef(float angle, float x, float y, float z) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glRotatef(angle, x, y, z));
    }

    public static void glScalef(float x, float y, float z) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glScalef(x, y, z));
    }

    /** buffer 参数录制时深拷贝（16 元素矩阵），执行后归还池。 */
    public static void glLoadMatrix(FloatBuffer matrix) {
        BridgeSupport.enqueueSnapshot(matrix, snapshot ->
                org.lwjgl.opengl.GL11.glLoadMatrix(snapshot.asFloatBuffer()));
    }

    public static void glLoadMatrix(DoubleBuffer matrix) {
        BridgeSupport.enqueueSnapshot(matrix, snapshot ->
                org.lwjgl.opengl.GL11.glLoadMatrix(snapshot.asDoubleBuffer()));
    }

    public static void glMultMatrix(FloatBuffer matrix) {
        BridgeSupport.enqueueSnapshot(matrix, snapshot ->
                org.lwjgl.opengl.GL11.glMultMatrix(snapshot.asFloatBuffer()));
    }

    public static void glMultMatrix(DoubleBuffer matrix) {
        BridgeSupport.enqueueSnapshot(matrix, snapshot ->
                org.lwjgl.opengl.GL11.glMultMatrix(snapshot.asDoubleBuffer()));
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    public static void glEnable(int cap) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glEnable(cap));
    }

    public static void glDisable(int cap) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glDisable(cap));
    }

    public static void glBlendFunc(int sfactor, int dfactor) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glBlendFunc(sfactor, dfactor));
    }

    public static void glAlphaFunc(int func, float ref) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glAlphaFunc(func, ref));
    }

    public static void glShadeModel(int mode) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glShadeModel(mode));
    }

    public static void glLineWidth(float width) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glLineWidth(width));
    }

    public static void glPointSize(float size) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glPointSize(size));
    }

    public static void glPolygonMode(int face, int mode) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glPolygonMode(face, mode));
    }

    public static void glHint(int target, int mode) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glHint(target, mode));
    }

    public static void glDepthMask(boolean flag) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glDepthMask(flag));
    }

    public static void glDepthFunc(int func) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glDepthFunc(func));
    }

    public static void glCullFace(int mode) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glCullFace(mode));
    }

    public static void glFrontFace(int mode) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glFrontFace(mode));
    }

    public static void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glColorMask(red, green, blue, alpha));
    }

    public static void glStencilFunc(int func, int ref, int mask) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glStencilFunc(func, ref, mask));
    }

    public static void glStencilOp(int fail, int zfail, int zpass) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glStencilOp(fail, zfail, zpass));
    }

    public static void glStencilMask(int mask) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glStencilMask(mask));
    }

    public static void glScissor(int x, int y, int width, int height) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glScissor(x, y, width, height));
    }

    public static void glViewport(int x, int y, int width, int height) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glViewport(x, y, width, height));
    }

    public static void glClear(int mask) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glClear(mask));
    }

    public static void glClearColor(float red, float green, float blue, float alpha) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glClearColor(red, green, blue, alpha));
    }

    public static void glClearStencil(int s) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glClearStencil(s));
    }

    public static void glColorMaterial(int face, int mode) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glColorMaterial(face, mode));
    }

    public static void glMaterialf(int face, int mode, float value) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glMaterialf(face, mode, value));
    }

    public static void glMateriali(int face, int mode, int value) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glMateriali(face, mode, value));
    }

    public static void glMaterial(int face, int mode, FloatBuffer params) {
        BridgeSupport.enqueueSnapshot(params, snapshot ->
                org.lwjgl.opengl.GL11.glMaterial(face, mode, snapshot.asFloatBuffer()));
    }

    public static void glMaterial(int face, int mode, IntBuffer params) {
        BridgeSupport.enqueueSnapshot(params, snapshot ->
                org.lwjgl.opengl.GL11.glMaterial(face, mode, snapshot.asIntBuffer()));
    }

    public static void glLightf(int light, int mode, float value) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glLightf(light, mode, value));
    }

    public static void glLighti(int light, int mode, int value) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glLighti(light, mode, value));
    }

    public static void glLight(int light, int mode, FloatBuffer params) {
        BridgeSupport.enqueueSnapshot(params, snapshot ->
                org.lwjgl.opengl.GL11.glLight(light, mode, snapshot.asFloatBuffer()));
    }

    public static void glLight(int light, int mode, IntBuffer params) {
        BridgeSupport.enqueueSnapshot(params, snapshot ->
                org.lwjgl.opengl.GL11.glLight(light, mode, snapshot.asIntBuffer()));
    }

    public static void glLightModelf(int mode, float value) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glLightModelf(mode, value));
    }

    public static void glLightModeli(int mode, int value) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glLightModeli(mode, value));
    }

    public static void glLightModel(int mode, FloatBuffer params) {
        BridgeSupport.enqueueSnapshot(params, snapshot ->
                org.lwjgl.opengl.GL11.glLightModel(mode, snapshot.asFloatBuffer()));
    }

    public static void glLightModel(int mode, IntBuffer params) {
        BridgeSupport.enqueueSnapshot(params, snapshot ->
                org.lwjgl.opengl.GL11.glLightModel(mode, snapshot.asIntBuffer()));
    }

    public static void glPushAttrib(int mask) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glPushAttrib(mask));
    }

    public static void glPopAttrib() {
        BridgeSupport.enqueue(org.lwjgl.opengl.GL11::glPopAttrib);
    }

    public static void glPushClientAttrib(int mask) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glPushClientAttrib(mask));
    }

    public static void glPopClientAttrib() {
        BridgeSupport.enqueue(org.lwjgl.opengl.GL11::glPopClientAttrib);
    }

    public static void glFlush() {
        BridgeSupport.enqueue(org.lwjgl.opengl.GL11::glFlush);
    }

    public static void glFinish() {
        BridgeSupport.enqueue(org.lwjgl.opengl.GL11::glFinish);
    }

    // ------------------------------------------------------------------
    // client state 与数组绘制
    // ------------------------------------------------------------------

    public static void glEnableClientState(int cap) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glEnableClientState(cap));
    }

    public static void glDisableClientState(int cap) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glDisableClientState(cap));
    }

    // -- pointer：录制快照进 ClientPointerState，不产生队列命令；
    //    快照由随后的 draw 命令携带（见类 javadoc 与 PointerSnapshot）。

    public static void glVertexPointer(int size, int stride, DoubleBuffer buffer) {
        BridgeSupport.pointerState().setVertex(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.VERTEX,
                size, org.lwjgl.opengl.GL11.GL_DOUBLE, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glVertexPointer(int size, int stride, FloatBuffer buffer) {
        BridgeSupport.pointerState().setVertex(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.VERTEX,
                size, org.lwjgl.opengl.GL11.GL_FLOAT, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glVertexPointer(int size, int stride, IntBuffer buffer) {
        BridgeSupport.pointerState().setVertex(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.VERTEX,
                size, org.lwjgl.opengl.GL11.GL_INT, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glVertexPointer(int size, int stride, ShortBuffer buffer) {
        BridgeSupport.pointerState().setVertex(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.VERTEX,
                size, org.lwjgl.opengl.GL11.GL_SHORT, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glVertexPointer(int size, int type, int stride, ByteBuffer buffer) {
        BridgeSupport.pointerState().setVertex(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.VERTEX,
                size, type, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    /** VBO 偏移形式：无 buffer 可快照，记录偏移标记。 */
    public static void glVertexPointer(int size, int type, int stride, long offset) {
        BridgeSupport.pointerState().setVertex(
                PointerSnapshot.ofOffset(PointerSnapshot.Kind.VERTEX, size, type, stride, offset));
    }

    public static void glColorPointer(int size, int stride, DoubleBuffer buffer) {
        BridgeSupport.pointerState().setColor(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.COLOR,
                size, org.lwjgl.opengl.GL11.GL_DOUBLE, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glColorPointer(int size, int stride, FloatBuffer buffer) {
        BridgeSupport.pointerState().setColor(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.COLOR,
                size, org.lwjgl.opengl.GL11.GL_FLOAT, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glColorPointer(int size, boolean unsigned, int stride, ByteBuffer buffer) {
        BridgeSupport.pointerState().setColor(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.COLOR,
                size, unsigned ? org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE : org.lwjgl.opengl.GL11.GL_BYTE,
                stride, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glColorPointer(int size, int type, int stride, ByteBuffer buffer) {
        BridgeSupport.pointerState().setColor(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.COLOR,
                size, type, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    /** VBO 偏移形式：无 buffer 可快照，记录偏移标记。 */
    public static void glColorPointer(int size, int type, int stride, long offset) {
        BridgeSupport.pointerState().setColor(
                PointerSnapshot.ofOffset(PointerSnapshot.Kind.COLOR, size, type, stride, offset));
    }

    public static void glTexCoordPointer(int size, int stride, DoubleBuffer buffer) {
        BridgeSupport.pointerState().setTexCoord(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.TEX_COORD,
                size, org.lwjgl.opengl.GL11.GL_DOUBLE, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glTexCoordPointer(int size, int stride, FloatBuffer buffer) {
        BridgeSupport.pointerState().setTexCoord(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.TEX_COORD,
                size, org.lwjgl.opengl.GL11.GL_FLOAT, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glTexCoordPointer(int size, int stride, IntBuffer buffer) {
        BridgeSupport.pointerState().setTexCoord(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.TEX_COORD,
                size, org.lwjgl.opengl.GL11.GL_INT, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glTexCoordPointer(int size, int stride, ShortBuffer buffer) {
        BridgeSupport.pointerState().setTexCoord(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.TEX_COORD,
                size, org.lwjgl.opengl.GL11.GL_SHORT, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glTexCoordPointer(int size, int type, int stride, ByteBuffer buffer) {
        BridgeSupport.pointerState().setTexCoord(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.TEX_COORD,
                size, type, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    /** VBO 偏移形式：无 buffer 可快照，记录偏移标记。 */
    public static void glTexCoordPointer(int size, int type, int stride, long offset) {
        BridgeSupport.pointerState().setTexCoord(
                PointerSnapshot.ofOffset(PointerSnapshot.Kind.TEX_COORD, size, type, stride, offset));
    }

    public static void glNormalPointer(int stride, DoubleBuffer buffer) {
        BridgeSupport.pointerState().setNormal(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.NORMAL,
                0, org.lwjgl.opengl.GL11.GL_DOUBLE, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glNormalPointer(int stride, FloatBuffer buffer) {
        BridgeSupport.pointerState().setNormal(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.NORMAL,
                0, org.lwjgl.opengl.GL11.GL_FLOAT, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glNormalPointer(int stride, IntBuffer buffer) {
        BridgeSupport.pointerState().setNormal(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.NORMAL,
                0, org.lwjgl.opengl.GL11.GL_INT, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glNormalPointer(int type, ByteBuffer buffer) {
        BridgeSupport.pointerState().setNormal(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.NORMAL,
                0, type, 0, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glNormalPointer(int type, int stride, ByteBuffer buffer) {
        BridgeSupport.pointerState().setNormal(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.NORMAL,
                0, type, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    /** VBO 偏移形式：无 buffer 可快照，记录偏移标记。 */
    public static void glNormalPointer(int type, int stride, long offset) {
        BridgeSupport.pointerState().setNormal(
                PointerSnapshot.ofOffset(PointerSnapshot.Kind.NORMAL, 0, type, stride, offset));
    }

    /** interleaved 使已记录的离散 pointer 快照失效（简化语义见 ClientPointerState）。 */
    public static void glInterleavedArrays(int format, int stride, ByteBuffer buffer) {
        BridgeSupport.pointerState().setInterleaved(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.INTERLEAVED,
                format, 0, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glInterleavedArrays(int format, int stride, DoubleBuffer buffer) {
        BridgeSupport.pointerState().setInterleaved(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.INTERLEAVED,
                format, 0, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glInterleavedArrays(int format, int stride, FloatBuffer buffer) {
        BridgeSupport.pointerState().setInterleaved(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.INTERLEAVED,
                format, 0, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glInterleavedArrays(int format, int stride, IntBuffer buffer) {
        BridgeSupport.pointerState().setInterleaved(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.INTERLEAVED,
                format, 0, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    public static void glInterleavedArrays(int format, int stride, ShortBuffer buffer) {
        BridgeSupport.pointerState().setInterleaved(PointerSnapshot.ofBuffer(PointerSnapshot.Kind.INTERLEAVED,
                format, 0, stride, BridgeSupport.pool().snapshot(buffer)));
    }

    // -- draw：携带 pointer 快照组

    public static void glDrawArrays(int mode, int first, int count) {
        enqueueDraw(() -> org.lwjgl.opengl.GL11.glDrawArrays(mode, first, count));
    }

    /** 索引 buffer 同样录制时快照，执行后归还。 */
    public static void glDrawElements(int mode, ByteBuffer indices) {
        ByteBuffer indexSnapshot = BridgeSupport.pool().snapshot(indices);
        PointerSnapshotGroup group = BridgeSupport.pointerState().capture();
        BridgeSupport.enqueue(() -> {
            try {
                group.apply();
                org.lwjgl.opengl.GL11.glDrawElements(mode, indexSnapshot);
            } finally {
                group.release();
                BridgeSupport.releaseSnapshot(indexSnapshot);
            }
        });
    }

    public static void glDrawElements(int mode, IntBuffer indices) {
        ByteBuffer indexSnapshot = BridgeSupport.pool().snapshot(indices);
        PointerSnapshotGroup group = BridgeSupport.pointerState().capture();
        BridgeSupport.enqueue(() -> {
            try {
                group.apply();
                org.lwjgl.opengl.GL11.glDrawElements(mode, indexSnapshot.asIntBuffer());
            } finally {
                group.release();
                BridgeSupport.releaseSnapshot(indexSnapshot);
            }
        });
    }

    public static void glDrawElements(int mode, ShortBuffer indices) {
        ByteBuffer indexSnapshot = BridgeSupport.pool().snapshot(indices);
        PointerSnapshotGroup group = BridgeSupport.pointerState().capture();
        BridgeSupport.enqueue(() -> {
            try {
                group.apply();
                org.lwjgl.opengl.GL11.glDrawElements(mode, indexSnapshot.asShortBuffer());
            } finally {
                group.release();
                BridgeSupport.releaseSnapshot(indexSnapshot);
            }
        });
    }

    /** VBO 索引偏移形式：无 buffer 可快照。 */
    public static void glDrawElements(int mode, int count, int type, long offset) {
        enqueueDraw(() -> org.lwjgl.opengl.GL11.glDrawElements(mode, count, type, offset));
    }

    // ------------------------------------------------------------------
    // 纹理
    // ------------------------------------------------------------------

    public static void glBindTexture(int target, int texture) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glBindTexture(target, texture));
    }

    /** 资源分配：阻塞通道取回真实纹理 id（预生成 stash 为后续演进点）。 */
    public static int glGenTextures() {
        return BridgeSupport.blockingGet(org.lwjgl.opengl.GL11::glGenTextures);
    }

    /** 渲染线程直接把 id 写入调用方 buffer；调用方阻塞期间 buffer 不被触碰。 */
    public static void glGenTextures(IntBuffer textures) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL11.glGenTextures(textures));
    }

    public static void glDeleteTextures(int texture) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glDeleteTextures(texture));
    }

    public static void glDeleteTextures(IntBuffer textures) {
        BridgeSupport.enqueueSnapshot(textures, snapshot ->
                org.lwjgl.opengl.GL11.glDeleteTextures(snapshot.asIntBuffer()));
    }

    /** pixels 允许为 null（仅分配纹理存储），此时无快照直接录制。 */
    public static void glTexImage1D(int target, int level, int internalformat, int width,
                                    int border, int format, int type, ByteBuffer pixels) {
        if (pixels == null) {
            BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTexImage1D(
                    target, level, internalformat, width, border, format, type, (ByteBuffer) null));
        } else {
            BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexImage1D(
                    target, level, internalformat, width, border, format, type, snapshot));
        }
    }

    public static void glTexImage1D(int target, int level, int internalformat, int width,
                                    int border, int format, int type, DoubleBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexImage1D(
                target, level, internalformat, width, border, format, type, snapshot.asDoubleBuffer()));
    }

    public static void glTexImage1D(int target, int level, int internalformat, int width,
                                    int border, int format, int type, FloatBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexImage1D(
                target, level, internalformat, width, border, format, type, snapshot.asFloatBuffer()));
    }

    public static void glTexImage1D(int target, int level, int internalformat, int width,
                                    int border, int format, int type, IntBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexImage1D(
                target, level, internalformat, width, border, format, type, snapshot.asIntBuffer()));
    }

    public static void glTexImage1D(int target, int level, int internalformat, int width,
                                    int border, int format, int type, ShortBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexImage1D(
                target, level, internalformat, width, border, format, type, snapshot.asShortBuffer()));
    }

    /** pixels 允许为 null（仅分配纹理存储），此时无快照直接录制。 */
    public static void glTexImage2D(int target, int level, int internalformat, int width, int height,
                                    int border, int format, int type, ByteBuffer pixels) {
        if (pixels == null) {
            BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTexImage2D(
                    target, level, internalformat, width, height, border, format, type, (ByteBuffer) null));
        } else {
            BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexImage2D(
                    target, level, internalformat, width, height, border, format, type, snapshot));
        }
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height,
                                    int border, int format, int type, DoubleBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexImage2D(
                target, level, internalformat, width, height, border, format, type, snapshot.asDoubleBuffer()));
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height,
                                    int border, int format, int type, FloatBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexImage2D(
                target, level, internalformat, width, height, border, format, type, snapshot.asFloatBuffer()));
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height,
                                    int border, int format, int type, IntBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexImage2D(
                target, level, internalformat, width, height, border, format, type, snapshot.asIntBuffer()));
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height,
                                    int border, int format, int type, ShortBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexImage2D(
                target, level, internalformat, width, height, border, format, type, snapshot.asShortBuffer()));
    }

    public static void glTexSubImage1D(int target, int level, int xoffset, int width,
                                       int format, int type, ByteBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexSubImage1D(
                target, level, xoffset, width, format, type, snapshot));
    }

    public static void glTexSubImage1D(int target, int level, int xoffset, int width,
                                       int format, int type, DoubleBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexSubImage1D(
                target, level, xoffset, width, format, type, snapshot.asDoubleBuffer()));
    }

    public static void glTexSubImage1D(int target, int level, int xoffset, int width,
                                       int format, int type, FloatBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexSubImage1D(
                target, level, xoffset, width, format, type, snapshot.asFloatBuffer()));
    }

    public static void glTexSubImage1D(int target, int level, int xoffset, int width,
                                       int format, int type, IntBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexSubImage1D(
                target, level, xoffset, width, format, type, snapshot.asIntBuffer()));
    }

    public static void glTexSubImage1D(int target, int level, int xoffset, int width,
                                       int format, int type, ShortBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexSubImage1D(
                target, level, xoffset, width, format, type, snapshot.asShortBuffer()));
    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height,
                                       int format, int type, ByteBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexSubImage2D(
                target, level, xoffset, yoffset, width, height, format, type, snapshot));
    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height,
                                       int format, int type, DoubleBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexSubImage2D(
                target, level, xoffset, yoffset, width, height, format, type, snapshot.asDoubleBuffer()));
    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height,
                                       int format, int type, FloatBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexSubImage2D(
                target, level, xoffset, yoffset, width, height, format, type, snapshot.asFloatBuffer()));
    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height,
                                       int format, int type, IntBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexSubImage2D(
                target, level, xoffset, yoffset, width, height, format, type, snapshot.asIntBuffer()));
    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height,
                                       int format, int type, ShortBuffer pixels) {
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexSubImage2D(
                target, level, xoffset, yoffset, width, height, format, type, snapshot.asShortBuffer()));
    }

    public static void glCopyTexImage2D(int target, int level, int internalformat,
                                        int x, int y, int width, int height, int border) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glCopyTexImage2D(
                target, level, internalformat, x, y, width, height, border));
    }

    public static void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset,
                                           int x, int y, int width, int height) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glCopyTexSubImage2D(
                target, level, xoffset, yoffset, x, y, width, height));
    }

    public static void glTexParameteri(int target, int pname, int param) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTexParameteri(target, pname, param));
    }

    public static void glTexParameterf(int target, int pname, float param) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTexParameterf(target, pname, param));
    }

    /** 向量版纹理参数：buffer 快照后入队。 */
    public static void glTexParameter(int target, int pname, FloatBuffer params) {
        BridgeSupport.enqueueSnapshot(params, snapshot ->
                org.lwjgl.opengl.GL11.glTexParameter(target, pname, snapshot.asFloatBuffer()));
    }

    /** 纹理环境参数（固定管线）。 */
    public static void glTexEnvf(int target, int pname, float param) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTexEnvf(target, pname, param));
    }

    /** 阻塞 getter：逐级纹理属性回读。 */
    public static int glGetTexLevelParameteri(int target, int level, int pname) {
        return BridgeSupport.blockingGet(() ->
                org.lwjgl.opengl.GL11.glGetTexLevelParameteri(target, level, pname));
    }

    public static void glPixelStorei(int pname, int param) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glPixelStorei(pname, param));
    }

    /** 两个 buffer 参数分别快照，执行后逐一归还。 */
    public static void glPrioritizeTextures(IntBuffer textures, FloatBuffer priorities) {
        ByteBuffer textureSnapshot = BridgeSupport.pool().snapshot(textures);
        ByteBuffer prioritySnapshot = BridgeSupport.pool().snapshot(priorities);
        BridgeSupport.enqueue(() -> {
            try {
                org.lwjgl.opengl.GL11.glPrioritizeTextures(
                        textureSnapshot.asIntBuffer(), prioritySnapshot.asFloatBuffer());
            } finally {
                BridgeSupport.releaseSnapshot(textureSnapshot);
                BridgeSupport.releaseSnapshot(prioritySnapshot);
            }
        });
    }

    // ------------------------------------------------------------------
    // display list（本阶段按普通命令入队，ListManager 式重放后续轮再做）
    // ------------------------------------------------------------------

    /** 资源分配：阻塞通道取回真实 list 基址。 */
    public static int glGenLists(int range) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glGenLists(range));
    }

    /**
     * 本阶段录制语义：渲染线程执行到本命令时才编译真实 display list——
     * glNewList 到 glEndList 之间的命令在队列中天然有序，编译结果与单线程一致。
     */
    public static void glNewList(int list, int mode) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glNewList(list, mode));
    }

    public static void glEndList() {
        BridgeSupport.enqueue(org.lwjgl.opengl.GL11::glEndList);
    }

    public static void glCallList(int list) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glCallList(list));
    }

    public static void glDeleteLists(int list, int range) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glDeleteLists(list, range));
    }

    // ------------------------------------------------------------------
    // getter（阻塞通道；状态仿真为后续演进点，见类 javadoc）
    // ------------------------------------------------------------------

    public static int glGetInteger(int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glGetInteger(pname));
    }

    /** 渲染线程直接写入调用方 buffer；调用方阻塞期间 buffer 不被触碰。 */
    public static void glGetInteger(int pname, IntBuffer params) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL11.glGetInteger(pname, params));
    }

    public static float glGetFloat(int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glGetFloat(pname));
    }

    public static void glGetFloat(int pname, FloatBuffer params) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL11.glGetFloat(pname, params));
    }

    public static boolean glGetBoolean(int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glGetBoolean(pname));
    }

    public static void glGetBoolean(int pname, ByteBuffer params) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL11.glGetBoolean(pname, params));
    }

    public static String glGetString(int name) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glGetString(name));
    }

    public static boolean glIsEnabled(int cap) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glIsEnabled(cap));
    }

    public static boolean glIsTexture(int texture) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glIsTexture(texture));
    }

    public static boolean glIsList(int list) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glIsList(list));
    }

    public static int glGetError() {
        return BridgeSupport.blockingGet(org.lwjgl.opengl.GL11::glGetError);
    }

    /** 回读语义强依赖执行完成，阻塞通道；渲染线程直接写入调用方 buffer。 */
    public static void glReadPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL11.glReadPixels(x, y, width, height, format, type, pixels));
    }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, DoubleBuffer pixels) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL11.glReadPixels(x, y, width, height, format, type, pixels));
    }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, FloatBuffer pixels) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL11.glReadPixels(x, y, width, height, format, type, pixels));
    }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, IntBuffer pixels) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL11.glReadPixels(x, y, width, height, format, type, pixels));
    }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, ShortBuffer pixels) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL11.glReadPixels(x, y, width, height, format, type, pixels));
    }
}
