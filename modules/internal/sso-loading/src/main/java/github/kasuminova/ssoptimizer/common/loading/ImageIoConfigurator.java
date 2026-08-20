package github.kasuminova.ssoptimizer.common.loading;

import org.apache.log4j.Logger;

import javax.imageio.ImageIO;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java ImageIO 系统配置器。
 * <p>
 * 禁用 ImageIO 内置缓存以降低内存占用，仅在首次调用时生效。
 */
public final class ImageIoConfigurator {
    private static final Logger        LOGGER     = Logger.getLogger(ImageIoConfigurator.class);
    private static final AtomicBoolean CONFIGURED = new AtomicBoolean();

    private ImageIoConfigurator() {
    }

    public static void configure() {
        if (!CONFIGURED.compareAndSet(false, true)) {
            return;
        }

        ImageIO.setUseCache(false);
        LOGGER.info("[SSOptimizer] Disabled ImageIO disk cache for startup resource loading");
    }
}