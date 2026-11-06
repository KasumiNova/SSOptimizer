package github.kasuminova.ssoptimizer.common.save;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/** 新引擎安装前阻止 Memory 引用解析删除键，并在安装后重放。 */
public final class MemoryRefResolveGuard {
    private static final Logger LOGGER = Logger.getLogger(MemoryRefResolveGuard.class);

    public interface PendingResolution {
        void ssoptimizer$rerunResolution();
    }

    private static volatile boolean unsafeWindow;

    private static volatile Object engineAtHead;

    private static final List<PendingResolution> PENDING = new ArrayList<>();

    private MemoryRefResolveGuard() {
    }

    public static boolean isUnsafeWindow() {
        return unsafeWindow;
    }

    public static void enterLoad() {
        enterLoad(com.fs.starfarer.campaign.CampaignEngine.getInstance());
    }

    static void enterLoad(final Object currentEngine) {
        unsafeWindow = true;
        engineAtHead = currentEngine;
    }

    public static void onEngineInstalled(final Object installedEngine) {
        if (!unsafeWindow || installedEngine == engineAtHead) {
            return;
        }
        unsafeWindow = false;
        runFixups();
    }

    public static void loadFinished() {
        unsafeWindow = false;
        engineAtHead = null;
        synchronized (PENDING) {
            PENDING.clear();
        }
    }

    public static void recordSuppressed(final PendingResolution memory) {
        synchronized (PENDING) {
            PENDING.add(memory);
        }
    }

    private static void runFixups() {
        final List<PendingResolution> batch;
        synchronized (PENDING) {
            if (PENDING.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        int failures = 0;
        for (final PendingResolution memory : batch) {
            try {
                memory.ssoptimizer$rerunResolution();
            } catch (Throwable t) {
                failures++;
                LOGGER.error("[SSOptimizer] Memory 引用重放解析失败", t);
            }
        }
        LOGGER.info(String.format(
                "[SSOptimizer] 读档窗口守卫：重放 %d 个 Memory 的引用解析（失败 %d）", batch.size(), failures));
    }
}
