package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

/**
 * org.lwjgl.opengl.GL11 的 bridge 镜像（垂直切片子集）。
 * <p>
 * 动机：ASM 重定向阶段会把游戏/模组字节码中 INVOKESTATIC owner
 * {@code org/lwjgl/opengl/GL11} 改写到本类，使所有固定管线调用不再直接触碰 GL
 * 上下文，而是封成 {@code GlCommand} 入队到 {@link RenderQueue} 当前帧，由渲染
 * 线程执行。本类与 LWJGL 保持同签名静态方法，保证改写后调用点无需额外适配。
 * <p>
 * 本阶段（骨架）：每个方法 1:1 录制一条命令，命令体直接调真
 * {@link org.lwjgl.opengl.GL11}，仅证明录制模式成立。后续阶段将替换实现：
 * <ul>
 *   <li>immediate 顶点流（glBegin/glVertex2f/glTexCoord2f/glColor4ub/glEnd）被
 *       顶点拦截器按 CPU 仿真矩阵当场变换进批量缓冲，glEnd 转 draw 命令；</li>
 *   <li>矩阵操作（glMatrixMode/glLoadIdentity/glPushMatrix/glPopMatrix/
 *       glTranslatef/glRotatef/glScalef/glOrtho）改走主线程 CPU 仿真栈，
 *       不再产生 GL 命令；</li>
 *   <li>glFlush/glFinish 按 FR 经验评估抹成 no-op（语义由帧同步点统一保证）。</li>
 * </ul>
 * 注入：{@link #install(RenderQueue)} 装配命令消费者（游戏接入时由 bootstrap
 * 安装真实队列；单测安装假队列验证录制行为）。未安装时调用直接抛
 * {@link IllegalStateException}——桥接类没有可回退的直通路径。
 */
public final class GL11 {
    private static volatile RenderQueue queue;

    private GL11() {
    }

    /**
     * 安装命令消费者。游戏接入时由 bootstrap 在 ASM 重定向生效前调用。
     *
     * @param renderQueue 渲染队列实例
     */
    public static void install(RenderQueue renderQueue) {
        queue = renderQueue;
    }

    /** 测试用：卸载已安装的队列，避免用例间静态状态串扰。 */
    static void uninstall() {
        queue = null;
    }

    private static RenderQueue queue() {
        RenderQueue q = queue;
        if (q == null) {
            throw new IllegalStateException("[SSOptimizer] bridge GL11 的 RenderQueue 未安装（GL11.install 未被调用）");
        }
        return q;
    }

    public static void glClear(int mask) {
        queue().submit(() -> org.lwjgl.opengl.GL11.glClear(mask));
    }

    public static void glClearColor(float red, float green, float blue, float alpha) {
        queue().submit(() -> org.lwjgl.opengl.GL11.glClearColor(red, green, blue, alpha));
    }

    public static void glViewport(int x, int y, int width, int height) {
        queue().submit(() -> org.lwjgl.opengl.GL11.glViewport(x, y, width, height));
    }

    public static void glEnable(int cap) {
        queue().submit(() -> org.lwjgl.opengl.GL11.glEnable(cap));
    }

    public static void glDisable(int cap) {
        queue().submit(() -> org.lwjgl.opengl.GL11.glDisable(cap));
    }

    public static void glBlendFunc(int sfactor, int dfactor) {
        queue().submit(() -> org.lwjgl.opengl.GL11.glBlendFunc(sfactor, dfactor));
    }

    public static void glMatrixMode(int mode) {
        queue().submit(() -> org.lwjgl.opengl.GL11.glMatrixMode(mode));
    }

    public static void glLoadIdentity() {
        queue().submit(org.lwjgl.opengl.GL11::glLoadIdentity);
    }

    public static void glPushMatrix() {
        queue().submit(org.lwjgl.opengl.GL11::glPushMatrix);
    }

    public static void glPopMatrix() {
        queue().submit(org.lwjgl.opengl.GL11::glPopMatrix);
    }

    public static void glTranslatef(float x, float y, float z) {
        queue().submit(() -> org.lwjgl.opengl.GL11.glTranslatef(x, y, z));
    }

    public static void glRotatef(float angle, float x, float y, float z) {
        queue().submit(() -> org.lwjgl.opengl.GL11.glRotatef(angle, x, y, z));
    }

    public static void glScalef(float x, float y, float z) {
        queue().submit(() -> org.lwjgl.opengl.GL11.glScalef(x, y, z));
    }

    public static void glBegin(int mode) {
        queue().submit(() -> org.lwjgl.opengl.GL11.glBegin(mode));
    }

    public static void glEnd() {
        queue().submit(org.lwjgl.opengl.GL11::glEnd);
    }

    public static void glVertex2f(float x, float y) {
        queue().submit(() -> org.lwjgl.opengl.GL11.glVertex2f(x, y));
    }

    public static void glTexCoord2f(float s, float t) {
        queue().submit(() -> org.lwjgl.opengl.GL11.glTexCoord2f(s, t));
    }

    public static void glColor4ub(byte red, byte green, byte blue, byte alpha) {
        queue().submit(() -> org.lwjgl.opengl.GL11.glColor4ub(red, green, blue, alpha));
    }

    public static void glBindTexture(int target, int texture) {
        queue().submit(() -> org.lwjgl.opengl.GL11.glBindTexture(target, texture));
    }

    public static void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {
        queue().submit(() -> org.lwjgl.opengl.GL11.glOrtho(left, right, bottom, top, zNear, zFar));
    }

    public static void glFlush() {
        queue().submit(org.lwjgl.opengl.GL11::glFlush);
    }

    public static void glFinish() {
        queue().submit(org.lwjgl.opengl.GL11::glFinish);
    }
}
