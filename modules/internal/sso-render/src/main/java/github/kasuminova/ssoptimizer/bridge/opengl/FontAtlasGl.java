package github.kasuminova.ssoptimizer.bridge.opengl;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * TTF 动态字形图集（sso-font {@code GlyphAtlasPage}）的全部真实 GL 操作收口。
 * <p>
 * 为什么必须在本包：{@code RenderThreadRedirector} 会把 bridge 包之外的类中对
 * {@code org.lwjgl.opengl.*} 的调用改写成 bridge 录制调用。图集的创建/上传/删除
 * 命令体在渲染线程上执行，必须调真 GL——若放在 sso-font 侧，命令体里的裸 lwjgl
 * 调用会被二次重定向成「再录制一次」，真实上传被延迟到后续帧且绑定交错污染其他
 * 纹理（实机症状：文字压成横条、背景贴图被图集污染）。bridge 包整体在改写排除
 * 表内，本类的裸 lwjgl 调用（与 {@link BridgeSupport} 同约定，全限定名书写以
 * 避免与本包 bridge 镜像类同名混淆）保证到达真实驱动。
 * <p>
 * 线程前提：全部方法<b>只能在渲染线程执行</b>（经 {@code GlCommand} 命令体或
 * {@code GlDispatch.allocate} 资源任务到达渲染线程后调用）。其他线程调用即
 * 调用方 bug——无 GL 上下文，行为未定义。
 * <p>
 * 状态透明：进入时保存旧 {@code GL_TEXTURE_BINDING_2D} 绑定与旧
 * {@code GL_UNPACK_ALIGNMENT}，退出前恢复（finally 保证）；上传前强制
 * {@code GL_UNPACK_ROW_LENGTH/GL_UNPACK_SKIP_ROWS/GL_UNPACK_SKIP_PIXELS}=0、
 * {@code GL_UNPACK_ALIGNMENT}=1（单通道紧密行，语义同 sso-loading 的
 * {@code TextureUploadHelper}）——调用方（渲染线程命令流上下文）的 GL 状态
 * 在方法返回后与进入前逐点一致。
 */
public final class FontAtlasGl {

    private FontAtlasGl() {
    }

    /**
     * 创建 size×size 的 GL_ALPHA8 图集纹理（LINEAR / CLAMP_TO_EDGE，无初始数据）。
     * 仅渲染线程；调用方绑定状态透明（进入/退出绑定一致）。
     *
     * @param size 页边长（像素，&gt;0）
     * @return 新纹理 id
     */
    public static int createAlphaTexture(final int size) {
        final int previousBinding = org.lwjgl.opengl.GL11.glGetInteger(
                org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        try {
            final int id = org.lwjgl.opengl.GL11.glGenTextures();
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, id);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            org.lwjgl.opengl.GL11.glTexImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                    org.lwjgl.opengl.GL11.GL_ALPHA8, size, size, 0,
                    org.lwjgl.opengl.GL11.GL_ALPHA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
                    (ByteBuffer) null);
            return id;
        } finally {
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, previousBinding);
        }
    }

    /**
     * 把一组脏矩形（{@code {x, y, w, h}} + 页内定位视图负载）上传到图集纹理。
     * 仅渲染线程；绑定与 unpack 状态透明。
     * <p>
     * 负载语义（P4 起）：每个 payload 是图集页 staging direct 缓冲的视图，
     * position 已定位到矩形原点（y*pageSize+x），行间步进由
     * GL_UNPACK_ROW_LENGTH=pageSize 描述——零分配零拷贝；视图持有 staging
     * 引用，命令执行期间 staging 内存不会被回收。
     *
     * @param textureId 目标图集纹理
     * @param pageSize  页边长（像素），用于脏矩形越界 fail-fast 校验与行步进
     * @param rects     脏矩形列表（与 payloads 一一对应）
     * @param payloads  各矩形的页内定位视图（remaining ≥ (h-1)*pageSize+w）
     */
    public static void uploadAlphaRects(final int textureId,
                                        final int pageSize,
                                        final List<int[]> rects,
                                        final List<ByteBuffer> payloads) {
        if (rects.size() != payloads.size()) {
            throw new IllegalStateException("[SSOptimizer] 图集上传 rects/payloads 不等长: "
                    + rects.size() + "/" + payloads.size());
        }
        final int previousBinding = org.lwjgl.opengl.GL11.glGetInteger(
                org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        final int previousAlignment = org.lwjgl.opengl.GL11.glGetInteger(
                org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT);
        final int previousRowLength = org.lwjgl.opengl.GL11.glGetInteger(
                org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH);
        try {
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, textureId);
            // 单通道页内步进：payload 定位到矩形原点，跨行由 ROW_LENGTH 描述
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, pageSize);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS, 0);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS, 0);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT, 1);
            for (int i = 0; i < rects.size(); i++) {
                final int[] rect = rects.get(i);
                if (rect[0] < 0 || rect[1] < 0 || rect[2] <= 0 || rect[3] <= 0
                        || rect[0] + rect[2] > pageSize || rect[1] + rect[3] > pageSize) {
                    throw new IllegalStateException("[SSOptimizer] 图集脏矩形越界: rect="
                            + rect[0] + "," + rect[1] + " " + rect[2] + "x" + rect[3]
                            + " pageSize=" + pageSize);
                }
                org.lwjgl.opengl.GL11.glTexSubImage2D(
                        org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                        rect[0], rect[1], rect[2], rect[3],
                        org.lwjgl.opengl.GL11.GL_ALPHA,
                        org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
                        payloads.get(i));
            }
        } finally {
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, previousBinding);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT, previousAlignment);
            org.lwjgl.opengl.GL11.glPixelStorei(org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH, previousRowLength);
        }
    }

    /**
     * 删除图集纹理（0 = 无操作）。仅渲染线程；不触碰绑定状态。
     *
     * @param textureId 待删除纹理 id
     */
    public static void deleteTexture(final int textureId) {
        if (textureId != 0) {
            org.lwjgl.opengl.GL11.glDeleteTextures(textureId);
        }
    }
}
