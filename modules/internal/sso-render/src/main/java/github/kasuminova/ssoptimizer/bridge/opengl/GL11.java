package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicReferenceArray;

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
 *   <li>immediate 顶点流（glBegin/glEnd 与 glVertex/glTexCoord/glColor/
 *       glNormal3f 族）：编码进逐线程 {@link VertexStream}，在 glEnd/任一非流式
 *       命令/阻塞通道 drain-first 之前打包成单条回放命令落帧——流段与命令
 *       对象在帧列表中共享同一有序序列，回放与原调用逐指令等价；</li>
 *   <li>无参/标量参数命令：1:1 录制一条命令，命令体调真
 *       {@link org.lwjgl.opengl.GL11}；</li>
 *   <li>buffer 参数命令（glTexImage/glBufferData/glLoadMatrix 等）：录制时经
 *       {@link BridgeSupport#pool()} 深拷贝为池化快照，命令执行后归还，调用方
 *       录制后改写源 buffer 不影响命令；</li>
 *   <li>client pointer（glVertexPointer 等）：录制时刻快照进
 *       {@link ClientPointerState}，draw 命令（glDrawArrays/glDrawElements/
 *       glArrayElement）携带快照组在执行前全量重放——简化语义与 FR
 *       ClientAttribTracker 的差异见 {@link PointerSnapshot} 的 javadoc；</li>
 *   <li>getter（glGetInteger/glGetFloat/glGetBoolean/glIsEnabled
 *       /glReadPixels/glGetError 等）与资源分配
 *       （glGenTextures/glGenLists）：走 {@link RenderQueue#get} 阻塞通道
 *       （自动计入 StallDetector）；后续演进点是把高频 pname/资源 id 换成
 *       主线程侧状态仿真与预生成 stash（见盘点文档 getter 清单）。例外：
 *       glGetString 的五类结果（供应商/渲染器/版本/扩展/着色语言版本）在同一
 *       GL context 生命周期内不变，首次阻塞取回后录制侧缓存——游戏的
 *       SpriteBatch 每次构造都经此探测 VBO 能力，不缓存会打成稳态热点；</li>
 *   <li>display list（glNewList/glEndList/glCallList）：本阶段按普通命令入队
 *       ——即「渲染线程直接执行真实 display list 编译/调用」，语义等价于单线程，
 *       只是 display list 本体编译发生在渲染线程。ListManager 式命令帧重放
 *       （编译期展开为命令序列、跨帧复用）留待后续轮次。</li>
 * </ul>
 * 后续阶段计划：immediate 顶点流被顶点拦截器当场变换进批量缓冲（glEnd 转
 * draw 命令，取代本步的逐指令回放）；矩阵操作改走主线程 CPU 仿真栈不再产生
 * GL 命令；glFlush/glFinish 评估抹成 no-op（语义由帧同步点统一保证）。
 * <p>
 * 注入：{@link #install(RenderQueue)} 装配命令消费者（游戏接入时由 bootstrap
 * 安装真实队列；单测安装假队列验证录制行为）。未安装时调用直接抛
 * {@link IllegalStateException}——桥接类没有可回退的直通路径。
 */
public final class GL11 {
    /**
     * glGetString 录制侧缓存：五类结果在同一 GL context 生命周期内不变，
     * 槽位见 {@link #glGetString(int)}；{@link #uninstall()} 时清空。
     */
    private static final AtomicReferenceArray<String> STRING_CACHE = new AtomicReferenceArray<>(5);

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
        for (int i = 0; i < STRING_CACHE.length(); i++) {
            STRING_CACHE.set(i, null);
        }
    }

    // ------------------------------------------------------------------
    // draw：池化 DrawCommand 携带当前 client pointer 快照组
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // immediate 顶点流（流式录制：编码进逐线程 VertexStream，glEnd/非流式
    // 命令/阻塞通道前打包成单条回放命令落帧，见 VertexStream 的顺序语义）
    // ------------------------------------------------------------------

    public static void glBegin(int mode) {
        BridgeSupport.recordingContext().vertexStream.begin(mode);
    }

    public static void glEnd() {
        // 立即落帧（保持既有「段即批次」语义）：主线程流段在 glEnd 处原子入队，
        // 若延迟到后续命令统一 flush，挂起窗口内 aux 生产者线程（BoxUtil 等）
        // 提交的命令会先入队，造成帧列表顺序错乱（aux 命令本应在流段之后）。
        // 流内状态指令（streamEnable/streamBlendFunc 等）已把 sprite 的状态设置
        // 合并进本段，每 sprite 仍是一次落帧但命令数从 6 条降到 1 条流命令。
        RecordingContext context = BridgeSupport.recordingContext();
        context.vertexStream.end();
        BridgeSupport.flushVertexStream(context);
    }

    /**
     * 流内 glEnable(cap)：把状态设置编码进顶点流（段外执行），供 sprite 渲染
     * 路径（{@link SpriteRenderHelper}）把「每 sprite 一条非流式状态命令」改为
     * 流内指令，避免打断连续同状态 sprite 的流段合并。
     */
    public static void streamEnable(int cap) {
        // 簿记在录制点（=回放序）更新：流内指令回放期才改真实状态，主线程 getter
        // 仿真以命令流顺序为准（见 SimulatedGlState 类 javadoc）
        BridgeSupport.simulatedState().onEnable(cap);
        BridgeSupport.recordingContext().vertexStream.enable(cap);
    }

    /** 流内 glDisable(cap)，语义同 {@link #streamEnable(int)}。 */
    public static void streamDisable(int cap) {
        BridgeSupport.simulatedState().onDisable(cap);
        BridgeSupport.recordingContext().vertexStream.disable(cap);
    }

    /** 流内 glBlendFunc(src, dst)，语义同 {@link #streamEnable(int)}。 */
    public static void streamBlendFunc(int src, int dst) {
        BridgeSupport.recordingContext().vertexStream.blendFunc(src, dst);
    }

    /**
     * 流内 glBindTexture(TEXTURE_2D, texture)：编码为段间指令（上一段 glEnd
     * 之后、下一段 glBegin 之前执行——glBindTexture 在 begin/end 段内非法，
     * 编码保证落在段边界）；同纹理连续 sprite 的重复绑定回放幂等，换纹理的
     * 绑定打断的是流内位置而非流段，连续 sprite 的 begin..end 段仍合并为
     * 一条流命令。
     */
    public static void streamBindTexture(int texture) {
        // 簿记在录制点更新（理由同 streamEnable）：保持 TEXTURE_BINDING_2D 仿真
        // 与命令流逐指令一致
        BridgeSupport.simulatedState().onBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texture);
        BridgeSupport.recordingContext().vertexStream.bindTexture(texture);
    }

    /**
     * 精灵四边形融合指令：sprite 渲染路径（{@link SpriteRenderHelper}）把
     * begin(QUADS)+4×(texCoord+vertex)+end 的整组调用压成一条流指令
     * （编码侧 13 次流调用 → 1 次），tex 角点顺序与
     * {@link VertexSink#spriteQuad} 约定一致。
     */
    public static void streamSpriteQuad(
            float x0, float y0, float x1, float y1,
            float x2, float y2, float x3, float y3,
            float texX, float texY, float texWidth, float texHeight) {
        BridgeSupport.recordingContext().vertexStream.spriteQuad(
                x0, y0, x1, y1, x2, y2, x3, y3, texX, texY, texWidth, texHeight);
    }

    public static void glVertex2f(float x, float y) {
        BridgeSupport.recordingContext().vertexStream.vertex2f(x, y);
    }

    public static void glVertex3f(float x, float y, float z) {
        BridgeSupport.recordingContext().vertexStream.vertex3f(x, y, z);
    }

    public static void glVertex2d(double x, double y) {
        BridgeSupport.recordingContext().vertexStream.vertex2d(x, y);
    }

    public static void glVertex3d(double x, double y, double z) {
        BridgeSupport.recordingContext().vertexStream.vertex3d(x, y, z);
    }

    public static void glTexCoord2f(float s, float t) {
        BridgeSupport.recordingContext().vertexStream.texCoord2f(s, t);
    }

    public static void glTexCoord2d(double s, double t) {
        BridgeSupport.recordingContext().vertexStream.texCoord2d(s, t);
    }

    public static void glColor4ub(byte red, byte green, byte blue, byte alpha) {
        BridgeSupport.recordingContext().vertexStream.color4ub(red, green, blue, alpha);
    }

    public static void glColor3ub(byte red, byte green, byte blue) {
        BridgeSupport.recordingContext().vertexStream.color3ub(red, green, blue);
    }

    public static void glColor3f(float red, float green, float blue) {
        BridgeSupport.recordingContext().vertexStream.color3f(red, green, blue);
    }

    public static void glColor4f(float red, float green, float blue, float alpha) {
        BridgeSupport.recordingContext().vertexStream.color4f(red, green, blue, alpha);
    }

    public static void glColor3d(double red, double green, double blue) {
        BridgeSupport.recordingContext().vertexStream.color3d(red, green, blue);
    }

    public static void glNormal3f(float nx, float ny, float nz) {
        BridgeSupport.recordingContext().vertexStream.normal3f(nx, ny, nz);
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

    /** 单元素绘制，同样携带 pointer 快照组（池化 {@link DrawCommand}）。 */
    public static void glArrayElement(int index) {
        DrawCommand command = BridgeSupport.acquireDrawCommand();
        command.setArrayElement(index, BridgeSupport.pointerState().capture());
        BridgeSupport.enqueue(command);
    }

    // ------------------------------------------------------------------
    // 矩阵
    // ------------------------------------------------------------------

    public static void glMatrixMode(int mode) {
        BridgeSupport.simulatedState().onMatrixMode(mode);
        BridgeSupport.enqueueState(StateDedup.TYPE_MATRIX_MODE, mode, 0, 0, 0,
                () -> org.lwjgl.opengl.GL11.glMatrixMode(mode));
    }

    public static void glLoadIdentity() {
        BridgeSupport.simulatedState().onLoadIdentity();
        BridgeSupport.enqueue(org.lwjgl.opengl.GL11::glLoadIdentity);
    }

    public static void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {
        BridgeSupport.simulatedState().onOrtho(left, right, bottom, top, zNear, zFar);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glOrtho(left, right, bottom, top, zNear, zFar));
    }

    public static void glPushMatrix() {
        BridgeSupport.simulatedState().onPushMatrix();
        BridgeSupport.enqueue(org.lwjgl.opengl.GL11::glPushMatrix);
    }

    public static void glPopMatrix() {
        BridgeSupport.simulatedState().onPopMatrix();
        BridgeSupport.enqueue(org.lwjgl.opengl.GL11::glPopMatrix);
    }

    public static void glTranslatef(float x, float y, float z) {
        BridgeSupport.simulatedState().onTranslate(x, y, z);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTranslatef(x, y, z));
    }

    public static void glTranslated(double x, double y, double z) {
        BridgeSupport.simulatedState().onTranslate(x, y, z);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTranslated(x, y, z));
    }

    public static void glRotatef(float angle, float x, float y, float z) {
        BridgeSupport.simulatedState().onRotate(angle, x, y, z);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glRotatef(angle, x, y, z));
    }

    public static void glScalef(float x, float y, float z) {
        BridgeSupport.simulatedState().onScale(x, y, z);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glScalef(x, y, z));
    }

    /** buffer 参数录制时深拷贝（16 元素矩阵），执行后归还池。 */
    public static void glLoadMatrix(FloatBuffer matrix) {
        BridgeSupport.simulatedState().onLoadMatrix(matrix);
        BridgeSupport.enqueueSnapshot(matrix, snapshot ->
                org.lwjgl.opengl.GL11.glLoadMatrix(snapshot.asFloatBuffer()));
    }

    public static void glLoadMatrix(DoubleBuffer matrix) {
        BridgeSupport.simulatedState().onLoadMatrix(matrix);
        BridgeSupport.enqueueSnapshot(matrix, snapshot ->
                org.lwjgl.opengl.GL11.glLoadMatrix(snapshot.asDoubleBuffer()));
    }

    public static void glMultMatrix(FloatBuffer matrix) {
        BridgeSupport.simulatedState().onMultMatrix(matrix);
        BridgeSupport.enqueueSnapshot(matrix, snapshot ->
                org.lwjgl.opengl.GL11.glMultMatrix(snapshot.asFloatBuffer()));
    }

    public static void glMultMatrix(DoubleBuffer matrix) {
        BridgeSupport.simulatedState().onMultMatrix(matrix);
        BridgeSupport.enqueueSnapshot(matrix, snapshot ->
                org.lwjgl.opengl.GL11.glMultMatrix(snapshot.asDoubleBuffer()));
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    public static void glEnable(int cap) {
        // 状态仿真簿记先行：enqueueState 去重丢弃重复命令时簿记仍正确（enable 幂等）
        BridgeSupport.simulatedState().onEnable(cap);
        BridgeSupport.enqueueState(StateDedup.TYPE_ENABLE, cap, 0, 0, 0,
                () -> org.lwjgl.opengl.GL11.glEnable(cap));
    }

    public static void glDisable(int cap) {
        BridgeSupport.simulatedState().onDisable(cap);
        BridgeSupport.enqueueState(StateDedup.TYPE_DISABLE, cap, 0, 0, 0,
                () -> org.lwjgl.opengl.GL11.glDisable(cap));
    }

    public static void glBlendFunc(int sfactor, int dfactor) {
        BridgeSupport.enqueueState(StateDedup.TYPE_BLEND_FUNC, sfactor, dfactor, 0, 0,
                () -> org.lwjgl.opengl.GL11.glBlendFunc(sfactor, dfactor));
    }

    public static void glAlphaFunc(int func, float ref) {
        BridgeSupport.simulatedState().onAlphaFunc(func, ref);
        BridgeSupport.enqueueState(StateDedup.TYPE_ALPHA_FUNC, func, Float.floatToRawIntBits(ref), 0, 0,
                () -> org.lwjgl.opengl.GL11.glAlphaFunc(func, ref));
    }

    public static void glShadeModel(int mode) {
        BridgeSupport.enqueueState(StateDedup.TYPE_SHADE_MODEL, mode, 0, 0, 0,
                () -> org.lwjgl.opengl.GL11.glShadeModel(mode));
    }

    public static void glLineWidth(float width) {
        BridgeSupport.enqueueState(StateDedup.TYPE_LINE_WIDTH, Float.floatToRawIntBits(width), 0, 0, 0,
                () -> org.lwjgl.opengl.GL11.glLineWidth(width));
    }

    public static void glPointSize(float size) {
        BridgeSupport.enqueueState(StateDedup.TYPE_POINT_SIZE, Float.floatToRawIntBits(size), 0, 0, 0,
                () -> org.lwjgl.opengl.GL11.glPointSize(size));
    }

    public static void glPolygonMode(int face, int mode) {
        BridgeSupport.enqueueState(StateDedup.TYPE_POLYGON_MODE, face, mode, 0, 0,
                () -> org.lwjgl.opengl.GL11.glPolygonMode(face, mode));
    }

    public static void glHint(int target, int mode) {
        BridgeSupport.enqueueState(StateDedup.TYPE_HINT, target, mode, 0, 0,
                () -> org.lwjgl.opengl.GL11.glHint(target, mode));
    }

    public static void glDepthMask(boolean flag) {
        BridgeSupport.enqueueState(StateDedup.TYPE_DEPTH_MASK, flag ? 1 : 0, 0, 0, 0,
                () -> org.lwjgl.opengl.GL11.glDepthMask(flag));
    }

    public static void glDepthFunc(int func) {
        BridgeSupport.enqueueState(StateDedup.TYPE_DEPTH_FUNC, func, 0, 0, 0,
                () -> org.lwjgl.opengl.GL11.glDepthFunc(func));
    }

    public static void glCullFace(int mode) {
        BridgeSupport.enqueueState(StateDedup.TYPE_CULL_FACE, mode, 0, 0, 0,
                () -> org.lwjgl.opengl.GL11.glCullFace(mode));
    }

    public static void glFrontFace(int mode) {
        BridgeSupport.enqueueState(StateDedup.TYPE_FRONT_FACE, mode, 0, 0, 0,
                () -> org.lwjgl.opengl.GL11.glFrontFace(mode));
    }

    public static void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        BridgeSupport.enqueueState(StateDedup.TYPE_COLOR_MASK, red ? 1 : 0, green ? 1 : 0, blue ? 1 : 0, alpha ? 1 : 0,
                () -> org.lwjgl.opengl.GL11.glColorMask(red, green, blue, alpha));
    }

    public static void glStencilFunc(int func, int ref, int mask) {
        BridgeSupport.enqueueState(StateDedup.TYPE_STENCIL_FUNC, func, ref, mask, 0,
                () -> org.lwjgl.opengl.GL11.glStencilFunc(func, ref, mask));
    }

    public static void glStencilOp(int fail, int zfail, int zpass) {
        BridgeSupport.enqueueState(StateDedup.TYPE_STENCIL_OP, fail, zfail, zpass, 0,
                () -> org.lwjgl.opengl.GL11.glStencilOp(fail, zfail, zpass));
    }

    public static void glStencilMask(int mask) {
        BridgeSupport.enqueueState(StateDedup.TYPE_STENCIL_MASK, mask, 0, 0, 0,
                () -> org.lwjgl.opengl.GL11.glStencilMask(mask));
    }

    public static void glScissor(int x, int y, int width, int height) {
        BridgeSupport.enqueueState(StateDedup.TYPE_SCISSOR, x, y, width, height,
                () -> org.lwjgl.opengl.GL11.glScissor(x, y, width, height));
    }

    public static void glViewport(int x, int y, int width, int height) {
        BridgeSupport.simulatedState().onViewport(x, y, width, height);
        BridgeSupport.enqueueState(StateDedup.TYPE_VIEWPORT, x, y, width, height,
                () -> org.lwjgl.opengl.GL11.glViewport(x, y, width, height));
    }

    public static void glClear(int mask) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glClear(mask));
    }

    public static void glClearColor(float red, float green, float blue, float alpha) {
        BridgeSupport.enqueueState(StateDedup.TYPE_CLEAR_COLOR,
                Float.floatToRawIntBits(red), Float.floatToRawIntBits(green),
                Float.floatToRawIntBits(blue), Float.floatToRawIntBits(alpha),
                () -> org.lwjgl.opengl.GL11.glClearColor(red, green, blue, alpha));
    }

    public static void glClearStencil(int s) {
        BridgeSupport.enqueueState(StateDedup.TYPE_CLEAR_STENCIL, s, 0, 0, 0,
                () -> org.lwjgl.opengl.GL11.glClearStencil(s));
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
        BridgeSupport.simulatedState().onPushAttrib(mask);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glPushAttrib(mask));
    }

    public static void glPopAttrib() {
        BridgeSupport.simulatedState().onPopAttrib();
        BridgeSupport.enqueue(org.lwjgl.opengl.GL11::glPopAttrib);
    }

    public static void glPushClientAttrib(int mask) {
        BridgeSupport.simulatedState().onPushClientAttrib(mask);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glPushClientAttrib(mask));
    }

    public static void glPopClientAttrib() {
        BridgeSupport.simulatedState().onPopClientAttrib();
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

    /** VBO 偏移形式：无 buffer 可快照，记录偏移与录制时刻的 ARRAY_BUFFER 绑定。 */
    public static void glVertexPointer(int size, int type, int stride, long offset) {
        ClientPointerState state = BridgeSupport.pointerState();
        state.setVertex(
                PointerSnapshot.ofOffset(PointerSnapshot.Kind.VERTEX, size, type, stride, offset,
                        state.arrayBufferBinding()));
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

    /** VBO 偏移形式：无 buffer 可快照，记录偏移与录制时刻的 ARRAY_BUFFER 绑定。 */
    public static void glColorPointer(int size, int type, int stride, long offset) {
        ClientPointerState state = BridgeSupport.pointerState();
        state.setColor(
                PointerSnapshot.ofOffset(PointerSnapshot.Kind.COLOR, size, type, stride, offset,
                        state.arrayBufferBinding()));
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

    /** VBO 偏移形式：无 buffer 可快照，记录偏移与录制时刻的 ARRAY_BUFFER 绑定。 */
    public static void glTexCoordPointer(int size, int type, int stride, long offset) {
        ClientPointerState state = BridgeSupport.pointerState();
        state.setTexCoord(
                PointerSnapshot.ofOffset(PointerSnapshot.Kind.TEX_COORD, size, type, stride, offset,
                        state.arrayBufferBinding()));
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

    /** VBO 偏移形式：无 buffer 可快照，记录偏移与录制时刻的 ARRAY_BUFFER 绑定。 */
    public static void glNormalPointer(int type, int stride, long offset) {
        ClientPointerState state = BridgeSupport.pointerState();
        state.setNormal(
                PointerSnapshot.ofOffset(PointerSnapshot.Kind.NORMAL, 0, type, stride, offset,
                        state.arrayBufferBinding()));
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

    // -- draw：池化 DrawCommand 携带 pointer 快照组

    public static void glDrawArrays(int mode, int first, int count) {
        DrawCommand command = BridgeSupport.acquireDrawCommand();
        command.setDrawArrays(mode, first, count, BridgeSupport.pointerState().capture());
        BridgeSupport.enqueue(command);
    }

    /** 索引 buffer 同样录制时快照，执行后归还。 */
    public static void glDrawElements(int mode, ByteBuffer indices) {
        DrawCommand command = BridgeSupport.acquireDrawCommand();
        command.setDrawElementsSnapshot(mode, BridgeSupport.pool().snapshot(indices),
                DrawCommand.VIEW_BYTE, BridgeSupport.pointerState().capture());
        BridgeSupport.enqueue(command);
    }

    public static void glDrawElements(int mode, IntBuffer indices) {
        DrawCommand command = BridgeSupport.acquireDrawCommand();
        command.setDrawElementsSnapshot(mode, BridgeSupport.pool().snapshot(indices),
                DrawCommand.VIEW_INT, BridgeSupport.pointerState().capture());
        BridgeSupport.enqueue(command);
    }

    public static void glDrawElements(int mode, ShortBuffer indices) {
        DrawCommand command = BridgeSupport.acquireDrawCommand();
        command.setDrawElementsSnapshot(mode, BridgeSupport.pool().snapshot(indices),
                DrawCommand.VIEW_SHORT, BridgeSupport.pointerState().capture());
        BridgeSupport.enqueue(command);
    }

    /** VBO 索引偏移形式：无 buffer 可快照。 */
    public static void glDrawElements(int mode, int count, int type, long offset) {
        DrawCommand command = BridgeSupport.acquireDrawCommand();
        command.setDrawElementsOffset(mode, count, type, offset, BridgeSupport.pointerState().capture());
        BridgeSupport.enqueue(command);
    }

    // ------------------------------------------------------------------
    // 纹理
    // ------------------------------------------------------------------

    public static void glBindTexture(int target, int texture) {
        BridgeSupport.simulatedState().onBindTexture(target, texture);
        BridgeSupport.enqueueState(StateDedup.TYPE_BIND_TEXTURE, target, texture, 0, 0,
                () -> org.lwjgl.opengl.GL11.glBindTexture(target, texture));
    }

    /** 资源分配：阻塞通道取回真实纹理 id（预生成 stash 为后续演进点）。 */
    public static int glGenTextures() {
        return BridgeSupport.blockingGetResource(org.lwjgl.opengl.GL11::glGenTextures);
    }

    /** 渲染线程直接把 id 写入调用方 buffer；调用方阻塞期间 buffer 不被触碰。 */
    public static void glGenTextures(IntBuffer textures) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.GL11.glGenTextures(textures));
    }

    public static void glDeleteTextures(int texture) {
        BridgeSupport.simulatedState().onDeleteTexture(texture);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glDeleteTextures(texture));
    }

    public static void glDeleteTextures(IntBuffer textures) {
        // 绝对读取遍历，不改动 buffer 位置；删除后各单元绑定簿记清零
        for (int i = textures.position(); i < textures.limit(); i++) {
            BridgeSupport.simulatedState().onDeleteTexture(textures.get(i));
        }
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
        BridgeSupport.enqueueState(StateDedup.TYPE_PIXEL_STOREI, pname, param, 0, 0,
                () -> org.lwjgl.opengl.GL11.glPixelStorei(pname, param));
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
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL11.glGenLists(range));
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
    // getter（FBO 绑定与 SimulatedGlState 簿记命中走录制侧仿真，其余阻塞通道）
    // ------------------------------------------------------------------

    public static int glGetInteger(int pname) {
        if (pname == org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT) {
            // 录制侧跟踪值直接返回：FBO 保存/恢复惯用法（雷达合成缓存 bakeCell 等）
            // 每帧多次调用，阻塞往返会把管线打回串行。游戏与模组的 FBO 绑定全部
            // 经 bridge 镜像（EXTFramebufferObject/GL30/ARBFramebufferObject），
            // 跟踪值与命令流一致（跨线程 FBO 使用存在记录序近似，见 BridgeSupport）
            return BridgeSupport.framebufferBinding();
        }
        if (BridgeSupport.isMainRecordingThread()) {
            // getter 回读状态仿真：簿记命中直接返回，未跟踪/失效回退阻塞通道；
            // 失效场景把读回的权威值采入簿记再同步（一次性成本，见 SimulatedGlState adopt 族）
            final Integer simulated = BridgeSupport.simulatedState().getInteger(pname);
            if (simulated != null) {
                return simulated;
            }
            final int authoritative = BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glGetInteger(pname));
            BridgeSupport.simulatedState().adoptInteger(pname, authoritative);
            return authoritative;
        }
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glGetInteger(pname));
    }

    /** 渲染线程直接写入调用方 buffer；调用方阻塞期间 buffer 不被触碰。 */
    public static void glGetInteger(int pname, IntBuffer params) {
        if (BridgeSupport.isMainRecordingThread()) {
            if (BridgeSupport.simulatedState().getInteger(pname, params)) {
                return;
            }
            if (pname == org.lwjgl.opengl.GL11.GL_VIEWPORT) {
                // VIEWPORT 失效再同步：阻塞读回后采入 4 值（写入起点即调用时 position）
                final int base = params.position();
                BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL11.glGetInteger(pname, params));
                BridgeSupport.simulatedState().adoptViewport(
                        params.get(base), params.get(base + 1), params.get(base + 2), params.get(base + 3));
                return;
            }
        }
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL11.glGetInteger(pname, params));
    }

    public static float glGetFloat(int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glGetFloat(pname));
    }

    public static void glGetFloat(int pname, FloatBuffer params) {
        if (BridgeSupport.isMainRecordingThread()) {
            if (BridgeSupport.simulatedState().getFloat(pname, params)) {
                return;
            }
            if (pname == org.lwjgl.opengl.GL11.GL_ALPHA_TEST_REF) {
                // ALPHA_TEST_REF 失效再同步（语义同 glGetInteger 的 VIEWPORT 分支）
                final int base = params.position();
                BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL11.glGetFloat(pname, params));
                BridgeSupport.simulatedState().adoptAlphaRef(params.get(base));
                return;
            }
        }
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL11.glGetFloat(pname, params));
    }

    public static boolean glGetBoolean(int pname) {
        if (BridgeSupport.isMainRecordingThread()) {
            // enable 能力簿记命中直接返回（SpriteBatch 收集守卫等逐 sprite 回读）；
            // 失效时阻塞读回并采入簿记再同步（一次性成本）
            final Boolean simulated = BridgeSupport.simulatedState().getBoolean(pname);
            if (simulated != null) {
                return simulated;
            }
            final boolean authoritative = BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glGetBoolean(pname));
            BridgeSupport.simulatedState().adoptBoolean(pname, authoritative);
            return authoritative;
        }
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glGetBoolean(pname));
    }

    public static void glGetBoolean(int pname, ByteBuffer params) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL11.glGetBoolean(pname, params));
    }

    /**
     * 字符串查询：五类 pname（供应商/渲染器/版本/扩展/着色语言版本）的结果
     * 在同一 GL context 生命周期内不变，首次走阻塞通道取回后录制侧缓存，
     * 后续调用零往返——游戏的 SpriteBatch 每次构造都经此探测 VBO 能力，
     * 逐次阻塞会把回读通道打成稳态热点。未识别的 pname 不缓存直通。
     */
    public static String glGetString(int name) {
        int slot = switch (name) {
            case org.lwjgl.opengl.GL11.GL_VENDOR -> 0;
            case org.lwjgl.opengl.GL11.GL_RENDERER -> 1;
            case org.lwjgl.opengl.GL11.GL_VERSION -> 2;
            case org.lwjgl.opengl.GL11.GL_EXTENSIONS -> 3;
            case org.lwjgl.opengl.GL20.GL_SHADING_LANGUAGE_VERSION -> 4;
            default -> -1;
        };
        if (slot < 0) {
            return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glGetString(name));
        }
        String cached = STRING_CACHE.get(slot);
        if (cached != null) {
            return cached;
        }
        String value = BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glGetString(name));
        STRING_CACHE.set(slot, value);
        return value;
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

    public static void glVertex2i(int x, int y) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glVertex2i(x, y));
    }

    public static void glColor4b(byte red, byte green, byte blue, byte alpha) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glColor4b(red, green, blue, alpha));
    }

    /** 单缓冲 draw buffer 选择（GL20.glDrawBuffers 的单目标形态）。 */
    public static void glDrawBuffer(int mode) {
        BridgeSupport.simulatedState().onDrawBuffer(mode);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glDrawBuffer(mode));
    }
}
