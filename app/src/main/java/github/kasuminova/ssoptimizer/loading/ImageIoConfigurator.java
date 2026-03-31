package github.kasuminova.ssoptimizer.loading;

import org.apache.log4j.Logger;

import javax.imageio.ImageIO;
import java.util.concurrent.atomic.AtomicBoolean;

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