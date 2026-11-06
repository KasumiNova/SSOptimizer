package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class GL11 extends GL11Gen {
    /** 缓存上下文生命周期内不变的字符串；卸载时清空。 */
    private static final AtomicReferenceArray<String> STRING_CACHE = new AtomicReferenceArray<>(5);

    private GL11() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    public static void uninstall() {
        BridgeSupport.uninstall();
        for (int i = 0; i < STRING_CACHE.length(); i++) {
            STRING_CACHE.set(i, null);
        }
    }



    public static void glBegin(int mode) {
        BridgeSupport.recordingContext().vertexStream.begin(mode);
    }

    public static void glEnd() {
        // 延迟落帧期间，aux 生产者命令可能先于挂起流段入帧；关闭该开关可恢复严格实时序。
        RecordingContext context = BridgeSupport.recordingContext();
        context.vertexStream.end();
        BridgeSupport.flushVertexStreamOnGlEnd(context);
    }

    public static void streamEnable(int cap) {
        // 流内状态在录制点更新簿记，保证 getter 按命令流顺序读取。
        BridgeSupport.simulatedState().onEnable(cap);
        BridgeSupport.recordingContext().vertexStream.enable(cap);
    }

    public static void streamDisable(int cap) {
        BridgeSupport.simulatedState().onDisable(cap);
        BridgeSupport.recordingContext().vertexStream.disable(cap);
    }

    public static void streamBlendFunc(int src, int dst) {
        BridgeSupport.recordingContext().vertexStream.blendFunc(src, dst);
    }

    public static void streamBindTexture(int texture) {
        BridgeSupport.simulatedState().onBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texture);
        BridgeSupport.recordingContext().vertexStream.bindTexture(texture);
    }

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

    public static void glRectf(float x1, float y1, float x2, float y2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glRectf(x1, y1, x2, y2));
    }

    public static void glRectd(double x1, double y1, double x2, double y2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glRectd(x1, y1, x2, y2));
    }

    public static void glReadBuffer(int mode) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glReadBuffer(mode));
    }

    public static void glArrayElement(int index) {
        DrawCommand command = BridgeSupport.acquireDrawCommand();
        command.setArrayElement(index, BridgeSupport.pointerState().capture());
        BridgeSupport.enqueue(command);
    }


    public static void glMatrixMode(int mode) {
        RecordingContext ctx = BridgeSupport.recordingContext();
        ctx.simulatedState.onMatrixMode(mode);
        if (BridgeSupport.shouldStreamMatrixOps(ctx)) {
            ctx.vertexStream.matrixMode(mode);
            return;
        }
        BridgeSupport.enqueueState(StateDedup.TYPE_MATRIX_MODE, mode, 0, 0, 0,
                () -> org.lwjgl.opengl.GL11.glMatrixMode(mode));
    }

    public static void glLoadIdentity() {
        RecordingContext ctx = BridgeSupport.recordingContext();
        ctx.simulatedState.onLoadIdentity();
        if (BridgeSupport.shouldStreamMatrixOps(ctx)) {
            ctx.vertexStream.loadIdentity();
            return;
        }
        BridgeSupport.enqueue(org.lwjgl.opengl.GL11::glLoadIdentity);
    }

    public static void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {
        BridgeSupport.simulatedState().onOrtho(left, right, bottom, top, zNear, zFar);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glOrtho(left, right, bottom, top, zNear, zFar));
    }

    public static void glPushMatrix() {
        RecordingContext ctx = BridgeSupport.recordingContext();
        ctx.simulatedState.onPushMatrix();
        if (BridgeSupport.shouldStreamMatrixOps(ctx)) {
            ctx.vertexStream.pushMatrix();
            return;
        }
        BridgeSupport.enqueue(org.lwjgl.opengl.GL11::glPushMatrix);
    }

    public static void glPopMatrix() {
        RecordingContext ctx = BridgeSupport.recordingContext();
        ctx.simulatedState.onPopMatrix();
        if (BridgeSupport.shouldStreamMatrixOps(ctx)) {
            ctx.vertexStream.popMatrix();
            return;
        }
        BridgeSupport.enqueue(org.lwjgl.opengl.GL11::glPopMatrix);
    }

    public static void glTranslatef(float x, float y, float z) {
        RecordingContext ctx = BridgeSupport.recordingContext();
        ctx.simulatedState.onTranslate(x, y, z);
        if (BridgeSupport.shouldStreamMatrixOps(ctx)) {
            ctx.vertexStream.translatef(x, y, z);
            return;
        }
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTranslatef(x, y, z));
    }

    public static void glTranslated(double x, double y, double z) {
        BridgeSupport.simulatedState().onTranslate(x, y, z);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTranslated(x, y, z));
    }

    public static void glRotatef(float angle, float x, float y, float z) {
        RecordingContext ctx = BridgeSupport.recordingContext();
        ctx.simulatedState.onRotate(angle, x, y, z);
        if (BridgeSupport.shouldStreamMatrixOps(ctx)) {
            ctx.vertexStream.rotatef(angle, x, y, z);
            return;
        }
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glRotatef(angle, x, y, z));
    }

    public static void glScalef(float x, float y, float z) {
        RecordingContext ctx = BridgeSupport.recordingContext();
        ctx.simulatedState.onScale(x, y, z);
        if (BridgeSupport.shouldStreamMatrixOps(ctx)) {
            ctx.vertexStream.scalef(x, y, z);
            return;
        }
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glScalef(x, y, z));
    }

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

    public static void glDepthRange(double zNear, double zFar) {
        final long nearBits = Double.doubleToRawLongBits(zNear);
        final long farBits = Double.doubleToRawLongBits(zFar);
        BridgeSupport.enqueueState(StateDedup.TYPE_DEPTH_RANGE,
                (int) (nearBits >>> 32), (int) nearBits,
                (int) (farBits >>> 32), (int) farBits,
                () -> org.lwjgl.opengl.GL11.glDepthRange(zNear, zFar));
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
        if (github.kasuminova.ssoptimizer.common.render.queue.RtTrace.enabled()) {
            github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                    "VIEWPORT", x, y, width, "h=" + height);
        }
        BridgeSupport.simulatedState().onViewport(x, y, width, height);
        BridgeSupport.enqueueState(StateDedup.TYPE_VIEWPORT, x, y, width, height,
                () -> org.lwjgl.opengl.GL11.glViewport(x, y, width, height));
    }

    public static void glClear(int mask) {
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "CLEAR", mask, BridgeSupport.framebufferBinding(), 0, null);
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

    public static void glClearDepth(double depth) {
        final long bits = Double.doubleToRawLongBits(depth);
        BridgeSupport.enqueueState(StateDedup.TYPE_CLEAR_DEPTH,
                (int) (bits >>> 32), (int) bits, 0, 0,
                () -> org.lwjgl.opengl.GL11.glClearDepth(depth));
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
        // ARRAY_BUFFER 绑定随 CLIENT_VERTEX_ARRAY_BIT 压栈，录制侧与执行侧簿记同步恢复。
        BridgeSupport.pointerState().pushClientAttrib(mask);
        BridgeSupport.enqueue(() -> {
            org.lwjgl.opengl.GL11.glPushClientAttrib(mask);
            BridgeSupport.onExecutedPushClientAttrib(mask);
        });
    }

    public static void glPopClientAttrib() {
        BridgeSupport.simulatedState().onPopClientAttrib();
        BridgeSupport.pointerState().popClientAttrib();
        final String recordThread = Thread.currentThread().getName();
        BridgeSupport.enqueue(() -> {
            org.lwjgl.opengl.GL11.glPopClientAttrib();
            BridgeSupport.onExecutedPopClientAttrib(recordThread);
        });
    }

    public static void glFlush() {
        BridgeSupport.enqueue(org.lwjgl.opengl.GL11::glFlush);
    }

    public static void glFinish() {
        BridgeSupport.enqueue(org.lwjgl.opengl.GL11::glFinish);
    }


    public static void glEnableClientState(int cap) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glEnableClientState(cap));
    }

    public static void glDisableClientState(int cap) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glDisableClientState(cap));
    }


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


    public static void glDrawArrays(int mode, int first, int count) {
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "DRAW_ARRAYS", mode, first, count, null);
        DrawCommand command = BridgeSupport.acquireDrawCommand();
        command.setDrawArrays(mode, first, count, BridgeSupport.pointerState().capture());
        BridgeSupport.enqueue(command);
    }

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

    public static void glDrawElements(int mode, int count, int type, long offset) {
        DrawCommand command = BridgeSupport.acquireDrawCommand();
        command.setDrawElementsOffset(mode, count, type, offset, BridgeSupport.pointerState().capture());
        BridgeSupport.enqueue(command);
    }


    public static void glBindTexture(int target, int texture) {
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "BIND_TEX", target, texture, 0, null);
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.traceBindWatch(target, texture);
        BridgeSupport.simulatedState().onBindTexture(target, texture);
        BridgeSupport.enqueueState(StateDedup.TYPE_BIND_TEXTURE, target, texture, 0, 0,
                () -> org.lwjgl.opengl.GL11.glBindTexture(target, texture));
    }

    public static int glGenTextures() {
        final int texture = BridgeSupport.blockingGetResource(org.lwjgl.opengl.GL11::glGenTextures);
        BridgeSupport.simulatedState().onGenTexture(texture);
        return texture;
    }

    public static void glGenTextures(IntBuffer textures) {
        final int base = textures.position();
        final int count = textures.remaining();
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.GL11.glGenTextures(textures));
        // 绝对读取采入 isTexture 簿记（glGenTextures 语义即名字预留，无需 bind）
        for (int i = base; i < base + count; i++) {
            BridgeSupport.simulatedState().onGenTexture(textures.get(i));
        }
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

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height,
                                    int border, int format, int type, ByteBuffer pixels) {
        BridgeSupport.simulatedState().onTexImage2D(target, level, internalformat, width, height);
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
        BridgeSupport.simulatedState().onTexImage2D(target, level, internalformat, width, height);
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexImage2D(
                target, level, internalformat, width, height, border, format, type, snapshot.asDoubleBuffer()));
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height,
                                    int border, int format, int type, FloatBuffer pixels) {
        BridgeSupport.simulatedState().onTexImage2D(target, level, internalformat, width, height);
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexImage2D(
                target, level, internalformat, width, height, border, format, type, snapshot.asFloatBuffer()));
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height,
                                    int border, int format, int type, IntBuffer pixels) {
        BridgeSupport.simulatedState().onTexImage2D(target, level, internalformat, width, height);
        BridgeSupport.enqueueSnapshot(pixels, snapshot -> org.lwjgl.opengl.GL11.glTexImage2D(
                target, level, internalformat, width, height, border, format, type, snapshot.asIntBuffer()));
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height,
                                    int border, int format, int type, ShortBuffer pixels) {
        BridgeSupport.simulatedState().onTexImage2D(target, level, internalformat, width, height);
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
        BridgeSupport.simulatedState().onTexImage2D(target, level, internalformat, width, height);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glCopyTexImage2D(
                target, level, internalformat, x, y, width, height, border));
    }

    public static void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset,
                                           int x, int y, int width, int height) {
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "COPY_TEX", width, height, BridgeSupport.framebufferBinding(), null);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glCopyTexSubImage2D(
                target, level, xoffset, yoffset, x, y, width, height));
    }

    public static void glTexParameteri(int target, int pname, int param) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTexParameteri(target, pname, param));
    }

    public static void glTexParameterf(int target, int pname, float param) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTexParameterf(target, pname, param));
    }

    public static void glTexParameter(int target, int pname, FloatBuffer params) {
        BridgeSupport.enqueueSnapshot(params, snapshot ->
                org.lwjgl.opengl.GL11.glTexParameter(target, pname, snapshot.asFloatBuffer()));
    }

    public static void glTexParameter(int target, int pname, IntBuffer params) {
        BridgeSupport.enqueueSnapshot(params, snapshot ->
                org.lwjgl.opengl.GL11.glTexParameter(target, pname, snapshot.asIntBuffer()));
    }


    public static void glTexImage1D(int target, int level, int internalformat, int width,
                                    int border, int format, int type, long pixels) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTexImage1D(
                target, level, internalformat, width, border, format, type, pixels));
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height,
                                    int border, int format, int type, long pixels) {
        BridgeSupport.simulatedState().onTexImage2D(target, level, internalformat, width, height);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTexImage2D(
                target, level, internalformat, width, height, border, format, type, pixels));
    }

    public static void glTexSubImage1D(int target, int level, int xoffset, int width,
                                       int format, int type, long pixels) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTexSubImage1D(
                target, level, xoffset, width, format, type, pixels));
    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset,
                                       int width, int height, int format, int type, long pixels) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTexSubImage2D(
                target, level, xoffset, yoffset, width, height, format, type, pixels));
    }

    public static void glTexEnvf(int target, int pname, float param) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glTexEnvf(target, pname, param));
    }

    /** 已簿记纹理的 level 0 属性在主线程本地读取，其余情况阻塞查询。 */
    public static int glGetTexLevelParameteri(int target, int level, int pname) {
        if (BridgeSupport.isMainRecordingThread()) {
            BridgeSupport.resyncSimulatedStateIfAuxDirty();
            final Integer simulated =
                    BridgeSupport.simulatedState().getTexLevelParam(target, level, pname);
            if (simulated != null) {
                return simulated;
            }
        }
        return BridgeSupport.blockingGet(() ->
                org.lwjgl.opengl.GL11.glGetTexLevelParameteri(target, level, pname));
    }

    public static void glPixelStorei(int pname, int param) {
        BridgeSupport.enqueueState(StateDedup.TYPE_PIXEL_STOREI, pname, param, 0, 0,
                () -> org.lwjgl.opengl.GL11.glPixelStorei(pname, param));
    }

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


    public static int glGenLists(int range) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL11.glGenLists(range));
    }

    /** 编译窗口内必须逐指令回放客户端数组，避免 display list 捕获复用缓冲中的陈旧数据。 */
    public static void glNewList(int list, int mode) {
        BridgeSupport.enqueue(() -> {
            BridgeSupport.onDisplayListCompileStart();
            org.lwjgl.opengl.GL11.glNewList(list, mode);
        });
    }

    public static void glEndList() {
        BridgeSupport.enqueue(() -> {
            org.lwjgl.opengl.GL11.glEndList();
            BridgeSupport.onDisplayListCompileEnd();
        });
    }

    public static void glCallList(int list) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glCallList(list));
    }

    public static void glDeleteLists(int list, int range) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glDeleteLists(list, range));
    }


    public static int glGetInteger(int pname) {
        // aux 提交会使主线程簿记失效，读取前必须屏障并重新同步。
        if (BridgeSupport.isMainRecordingThread()) {
            BridgeSupport.resyncSimulatedStateIfAuxDirty();
        }
        if (pname == org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT) {
            // FBO 绑定全部经 bridge 录制，主线程可直接读取跟踪值；跨线程使用仅为记录序近似。
            return BridgeSupport.framebufferBinding();
        }
        if (BridgeSupport.isMainRecordingThread()) {
            // 未跟踪或失效时阻塞读回权威值并重新采入簿记。
            final Integer simulated = BridgeSupport.simulatedState().getInteger(pname);
            if (simulated != null) {
                return simulated;
            }
            if (SimulatedGlState.isCapConstant(pname)) {
                // caps 常量（GL_MAX_* 族）上下文生命周期内不变：首次阻塞读回后永久缓存
                final Integer cached = BridgeSupport.simulatedState().cachedCap(pname);
                if (cached != null) {
                    return cached;
                }
                final int value = BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glGetInteger(pname));
                BridgeSupport.simulatedState().adoptCap(pname, value);
                return value;
            }
            final int authoritative = BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glGetInteger(pname));
            BridgeSupport.simulatedState().adoptInteger(pname, authoritative);
            return authoritative;
        }
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glGetInteger(pname));
    }

    public static void glGetInteger(int pname, IntBuffer params) {
        if (BridgeSupport.isMainRecordingThread()) {
            BridgeSupport.resyncSimulatedStateIfAuxDirty();
            if (BridgeSupport.simulatedState().getInteger(pname, params)) {
                return;
            }
            if (pname == org.lwjgl.opengl.GL11.GL_VIEWPORT) {
                // VIEWPORT 失效时经填充辅助读回 4 值并重新采入簿记。
                final int base = params.position();
                BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(params, 16,
                        buf -> org.lwjgl.opengl.GL11.glGetInteger(pname, buf)));
                BridgeSupport.simulatedState().adoptViewport(
                        params.get(base), params.get(base + 1), params.get(base + 2), params.get(base + 3));
                return;
            }
        }
        // LWJGL2 对 glGetInteger 缓冲变体固定要求 remaining ≥ 16（BufferChecks），
        // 小缓冲经填充辅助暂存执行后拷回（见 GetBufferFill）
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(params, 16,
                buf -> org.lwjgl.opengl.GL11.glGetInteger(pname, buf)));
    }

    public static float glGetFloat(int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glGetFloat(pname));
    }

    public static void glGetFloat(int pname, FloatBuffer params) {
        if (BridgeSupport.isMainRecordingThread()) {
            BridgeSupport.resyncSimulatedStateIfAuxDirty();
            if (BridgeSupport.simulatedState().getFloat(pname, params)) {
                return;
            }
            if (pname == org.lwjgl.opengl.GL11.GL_ALPHA_TEST_REF) {
                final int base = params.position();
                BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(params, 16,
                        buf -> org.lwjgl.opengl.GL11.glGetFloat(pname, buf)));
                BridgeSupport.simulatedState().adoptAlphaRef(params.get(base));
                return;
            }
        }
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(params, 16,
                buf -> org.lwjgl.opengl.GL11.glGetFloat(pname, buf)));
    }

    public static boolean glGetBoolean(int pname) {
        if (BridgeSupport.isMainRecordingThread()) {
            BridgeSupport.resyncSimulatedStateIfAuxDirty();
            // enable 簿记失效时阻塞读回并重新采入。
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
        BridgeSupport.blockingWait(() -> GetBufferFill.fillBooleans(params, 16,
                buf -> org.lwjgl.opengl.GL11.glGetBoolean(pname, buf)));
    }

    /** 上下文内不变的五类字符串首次阻塞查询后缓存，其他 pname 不缓存。 */
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

    /** 主线程读取名字簿记，aux 线程阻塞查询真实状态。 */
    public static boolean glIsTexture(int texture) {
        if (BridgeSupport.isMainRecordingThread()) {
            BridgeSupport.resyncSimulatedStateIfAuxDirty();
            return BridgeSupport.simulatedState().isTexture(texture);
        }
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glIsTexture(texture));
    }

    public static boolean glIsList(int list) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL11.glIsList(list));
    }

    public static int glGetError() {
        return BridgeSupport.blockingGet(org.lwjgl.opengl.GL11::glGetError);
    }

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

    public static void glDrawBuffer(int mode) {
        BridgeSupport.simulatedState().onDrawBuffer(mode);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL11.glDrawBuffer(mode));
    }
}
