package github.kasuminova.ssoptimizer.common.automation;

import github.kasuminova.ssoptimizer.api.save.SavePhaseTelemetry;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * 读档自动化驱动：在标题界面直接调用原版 {@code CampaignGameManager.loadGame} 入口，
 * 同步计时并写出遥测后退出进程。
 *
 * <p>动机：离线全保真 unmarshal 的环境装配成本过高（GL 单例、脚本仓库、模组 remap 层层级联），
 * 读档性能基准改为实机路径。该驱动复刻 {@code TitleScreenState} 读档对话框确认分支的
 * 调用序列（{@code CampaignEngine.resetInstance()} → {@code loadGame(saveDir, campaignState, campaignState)}），
 * 保证测的是游戏真实读档链路（含 SSOptimizer 自身的读档优化）。</p>
 *
 * <p>由 {@code TitleScreenAutomationMixin} 在 {@code TitleScreenState.advance} 尾部驱动；
 * 仅在 {@code ssoptimizer.automation.scenario=save_load_cycle} 时激活。</p>
 */
public final class SaveLoadCycleDriver {
    private static final Logger LOGGER = Logger.getLogger(SaveLoadCycleDriver.class);

    /** save_load_cycle 场景 ID。 */
    public static final String SCENARIO = "save_load_cycle";

    /** 读档目标存档目录系统属性（saves/ 下目录名或绝对路径）。 */
    public static final String SAVE_DIR_PROPERTY = "ssoptimizer.automation.saveload.saveDir";

    /** 读档前标题界面稳定帧数系统属性。 */
    public static final String SETTLE_FRAMES_PROPERTY = "ssoptimizer.automation.saveload.settleFrames";

    /**
     * 读档成功后是否立即执行保存（写侧基线采集），默认开启。
     */
    public static final String SAVE_AFTER_LOAD_PROPERTY = "ssoptimizer.automation.saveload.saveAfterLoad";

    /** 遥测文件名。 */
    public static final String TELEMETRY_FILE = "saveload-telemetry.json";

    private static final int DEFAULT_SETTLE_FRAMES = 30;

    private static volatile int settledFrames;
    private static volatile boolean attempted;

    private SaveLoadCycleDriver() {
    }

    /**
     * 标题界面 advance 尾部入口。
     *
     * @param session 标题界面会话（含 "campaign state in session"）
     */
    public static void tryAdvance(final Map<String, Object> session) {
        final AutomationConfig config = AutomationConfig.fromSystemProperties();
        if (!config.enabled() || !SCENARIO.equals(config.scenario()) || attempted) {
            return;
        }

        if (session == null || session.get("campaign state in session") == null) {
            return;
        }

        final int settleFrames = Integer.parseInt(
                System.getProperty(SETTLE_FRAMES_PROPERTY, String.valueOf(DEFAULT_SETTLE_FRAMES)));
        if (settledFrames++ < settleFrames) {
            return;
        }

        attempted = true;
        final String saveDirProp = System.getProperty(SAVE_DIR_PROPERTY, "").trim();
        if (saveDirProp.isEmpty()) {
            throw new IllegalStateException("save_load_cycle 需要 -D" + SAVE_DIR_PROPERTY + "=<存档目录名或绝对路径>");
        }

        final Path saveDir = resolveSaveDir(saveDirProp);
        LOGGER.info("[SSO-SaveLoad] loading save: " + saveDir);

        final Object campaignState = session.get("campaign state in session");
        try {
            // 与 TitleScreenState 读档确认分支一致：先重置 CampaignEngine 单例
            com.fs.starfarer.campaign.CampaignEngine.resetInstance();
            final long start = System.nanoTime();
            final String error = com.fs.starfarer.campaign.save.CampaignGameManager.loadGame(
                    saveDir.toAbsolutePath().toString(),
                    (com.fs.starfarer.campaign.CampaignState) campaignState,
                    (com.fs.starfarer.campaign.CampaignState) campaignState);
            final double loadMs = (System.nanoTime() - start) / 1e6;

            if (error != null) {
                writeTelemetry(config, saveDir, loadMs, -1, false, error, null);
                LOGGER.error("[SSO-SaveLoad] load failed: " + error);
                System.exit(1);
                return;
            }

            LOGGER.info(String.format(Locale.ROOT, "[SSO-SaveLoad] load OK: %.0f ms (unmarshal %d ms)",
                    loadMs, ServiceRegistry.require(SavePhaseTelemetry.class).lastUnmarshalMs()));

            // 写侧：读档成功后立即对同一存档目录执行完整保存，采集 marshal 基线
            double saveMs = -1;
            String saveError = null;
            if (Boolean.parseBoolean(System.getProperty(SAVE_AFTER_LOAD_PROPERTY, "true"))) {
                LOGGER.info("[SSO-SaveLoad] saving game...");
                final long saveStart = System.nanoTime();
                saveError = com.fs.starfarer.campaign.save.CampaignGameManager.saveGame(
                        (com.fs.starfarer.campaign.CampaignEngine.CampaignUI) campaignState, 0L, false);
                saveMs = (System.nanoTime() - saveStart) / 1e6;
                if (saveError != null) {
                    LOGGER.error("[SSO-SaveLoad] save failed: " + saveError);
                } else {
                    LOGGER.info(String.format(Locale.ROOT, "[SSO-SaveLoad] save OK: %.0f ms", saveMs));
                }
            }

            writeTelemetry(config, saveDir, loadMs, saveMs, true, null, saveError);
            System.exit(saveError == null ? 0 : 3);
        } catch (final Throwable t) {
            LOGGER.error("[SSO-SaveLoad] load crashed", t);
            writeTelemetry(config, saveDir, -1, -1, false, t.toString(), null);
            System.exit(2);
        }
    }

    private static Path resolveSaveDir(final String saveDirProp) {
        final Path direct = Path.of(saveDirProp);
        if (direct.isAbsolute()) {
            return direct;
        }
        // 游戏运行目录即游戏根（启动脚本 cd 到游戏根）
        return Path.of(System.getProperty("user.dir", "."), "saves", saveDirProp);
    }

    private static void writeTelemetry(final AutomationConfig config,
                                       final Path saveDir,
                                       final double loadMs,
                                       final double saveMs,
                                       final boolean success,
                                       final String error,
                                       final String saveError) {
        final String json = "{\n"
                + "  \"scenario\": \"" + SCENARIO + "\",\n"
                + "  \"saveDir\": \"" + saveDir.toString().replace("\\", "\\\\") + "\",\n"
                + "  \"success\": " + success + ",\n"
                + "  \"loadMs\": " + String.format(Locale.ROOT, "%.1f", loadMs) + ",\n"
                + "  \"unmarshalMs\": " + ServiceRegistry.require(SavePhaseTelemetry.class).lastUnmarshalMs() + ",\n"
                + "  \"saveMs\": " + String.format(Locale.ROOT, "%.1f", saveMs) + ",\n"
                + "  \"saveError\": "
                + (saveError == null ? "null" : "\"" + saveError.replace("\"", "'").replace("\n", " ") + "\"") + ",\n"
                + "  \"error\": " + (error == null ? "null" : "\"" + error.replace("\"", "'").replace("\n", " ") + "\"")
                + "\n"
                + "}\n";
        try {
            Files.createDirectories(config.outputDir());
            Files.writeString(config.outputDir().resolve(TELEMETRY_FILE), json);
        } catch (final IOException e) {
            throw new UncheckedIOException("写入读档遥测失败", e);
        }
    }

    /**
     * 重置测试状态。
     */
    public static void resetForTests() {
        settledFrames = 0;
        attempted = false;
    }
}
