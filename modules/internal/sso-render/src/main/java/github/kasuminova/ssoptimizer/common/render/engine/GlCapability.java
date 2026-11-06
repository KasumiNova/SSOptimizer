package github.kasuminova.ssoptimizer.common.render.engine;

import org.apache.log4j.Logger;
import org.lwjgl.opengl.GLContext;

public final class GlCapability {
    private static final Logger LOGGER = Logger.getLogger(GlCapability.class);

    public enum Mode {
        VBO_BATCH,
        IMMEDIATE
    }

    private GlCapability() {
    }

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

    public static Mode resolve(Mode requested, boolean gl15) {
        return switch (requested) {
            case VBO_BATCH -> gl15 ? Mode.VBO_BATCH : Mode.IMMEDIATE;
            case IMMEDIATE -> Mode.IMMEDIATE;
        };
    }

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
