package github.kasuminova.ssoptimizer.common.render.engine;

import org.apache.log4j.Logger;
import org.lwjgl.opengl.GLContext;

/**
 * 引擎渲染合批的 GL 能力探测与渲染模式决策。
 * <p>
 * 游戏运行于 LWJGL2 兼容 profile 上下文（未传 ContextAttribs），Linux 桌面驱动通常暴露
 * GL15+，但仍需运行时探测并按 VBO_BATCH → IMMEDIATE 降级链回退。
 * 模式解析与降级映射为纯函数（可单测）；{@link #detectBest()} 才是唯一触碰 GL 上下文的入口。
 * <p>
 * 注：曾实现 INSTANCED 模式（per-instance 属性 + 顶点着色器展开），实测在本游戏上下文内
 * 属性获取链路异常（几何错乱，二分至单属性点绘制仍不收敛），已整体移除；VBO_BATCH
 * 为本功能的最高模式。
 */
public final class GlCapability {
    private static final Logger LOGGER = Logger.getLogger(GlCapability.class);

    /** 合批渲染模式：动态 VBO 展开 / 立即模式回退。 */
    public enum Mode {
        /** CPU 展开三角形写入环形 VBO，固定管线 glVertexPointer 绘制（需 GL15）。 */
        VBO_BATCH,
        /** 回退到 {@link EngineRenderHelper} 的立即模式路径。 */
        IMMEDIATE
    }

    private GlCapability() {
    }

    /**
     * 解析 {@code -Dssoptimizer.render.shipengine.mode} 的配置值。
     *
     * @param raw 原始配置字符串，可为 null
     * @return 对应模式；无法识别时返回 null（调用方负责记日志并使用默认值）
     */
    public static Mode parseConfiguredMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase()) {
            case "vbo" -> Mode.VBO_BATCH;
            case "immediate" -> Mode.IMMEDIATE;
            default -> null;
        };
    }

    /**
     * 按降级链求解实际生效模式（纯函数，不触碰 GL）。
     *
     * @param requested 请求模式
     * @param gl15      上下文是否暴露 OpenGL 1.5
     * @return 实际可用模式；请求模式不可用时逐级降级
     */
    public static Mode resolve(Mode requested, boolean gl15) {
        return switch (requested) {
            case VBO_BATCH -> gl15 ? Mode.VBO_BATCH : Mode.IMMEDIATE;
            case IMMEDIATE -> Mode.IMMEDIATE;
        };
    }

    /**
     * 探测当前 GL 上下文支持的最高合批模式，探测结果与降级均输出日志。
     *
     * @param requested 用户请求模式
     * @return 实际生效模式
     */
    public static Mode detectBest(Mode requested) {
        boolean gl15 = GLContext.getCapabilities().OpenGL15;
        Mode resolved = resolve(requested, gl15);
        if (resolved != requested) {
            LOGGER.warn(String.format(
                    "[SSOptimizer] 引擎合批模式 %s 不可用（GL15=%s），降级为 %s",
                    requested, gl15, resolved));
        } else {
            LOGGER.info(String.format(
                    "[SSOptimizer] 引擎合批模式 %s 已启用（GL15=%s）", resolved, gl15));
        }
        return resolved;
    }
}
