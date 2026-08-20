package github.kasuminova.ssoptimizer.common.automation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 验收 ASTD 首版游戏内自动化 telemetry。
 *
 * <p>首版聚焦 Arc Flare + AOD-7：检查场景、舰船、武器、弹体、VFX preset、开火命令和截图证据。
 * 该类刻意保持零第三方 JSON 依赖，便于在烟测脚本和单元测试中直接复用。</p>
 */
public final class AutomationTelemetryVerifier {
    private static final String EXPECTED_SCENARIO = "arc_flare_aod7_basic";
    private static final String EXPECTED_SHIP_ID = "astd_arc_flare";
    private static final String EXPECTED_WEAPON_ID = "astd_aod7";
    private static final String EXPECTED_PROJECTILE_SPEC_ID = "astd_aod7_shot";
    private static final String EXPECTED_VFX_PRESET_ID = "aod7_shot";

    private AutomationTelemetryVerifier() {
    }

    /**
     * 验收指定 telemetry 文件。
     *
     * @param telemetryPath         ASTD telemetry JSON 路径
     * @param requireScreenshotFile 是否要求真实截图文件
     * @return 验收结果
     */
    public static VerificationResult verify(final Path telemetryPath, final boolean requireScreenshotFile) {
        final List<String> errors = new ArrayList<>();
        final String json;
        try {
            json = Files.readString(telemetryPath);
        } catch (IOException e) {
            return VerificationResult.fail(telemetryPath, List.of("telemetry missing: " + telemetryPath));
        }

        expectString(json, "scenario", EXPECTED_SCENARIO, errors);
        expectString(json, "state", "Completed", errors);
        expectString(json, "shipId", EXPECTED_SHIP_ID, errors);
        expectString(json, "weaponId", EXPECTED_WEAPON_ID, errors);
        expectString(json, "projectileSpecId", EXPECTED_PROJECTILE_SPEC_ID, errors);
        expectString(json, "vfxPresetId", EXPECTED_VFX_PRESET_ID, errors);

        expectTrue(json, "combatSceneObserved", errors);
        expectTrue(json, "shipObserved", errors);
        expectTrue(json, "weaponObserved", errors);
        expectTrue(json, "projectileObserved", errors);
        expectTrue(json, "vfxObserved", errors);
        expectTrue(json, "fireCommandIssued", errors);

        final String screenshotPath = stringValue(json, "screenshotPath");
        final String screenshotAttemptPath = stringValue(json, "screenshotAttemptPath");
        if (requireScreenshotFile) {
            if (screenshotPath == null || screenshotPath.isBlank()) {
                errors.add("screenshotPath: required but missing");
            } else if (!Files.isRegularFile(Path.of(screenshotPath))) {
                errors.add("screenshotPath: file does not exist: " + screenshotPath);
            }
        } else if (screenshotPath != null && !screenshotPath.isBlank()) {
            if (!Files.isRegularFile(Path.of(screenshotPath))) {
                errors.add("screenshotPath: declared file does not exist: " + screenshotPath);
            }
        } else if (screenshotAttemptPath == null || screenshotAttemptPath.isBlank()) {
            errors.add("evidence: expected screenshotPath or screenshotAttemptPath");
        } else if (!Files.isRegularFile(Path.of(screenshotAttemptPath))) {
            errors.add("screenshotAttemptPath: file does not exist: " + screenshotAttemptPath);
        }

        if (errors.isEmpty()) {
            return VerificationResult.pass(telemetryPath);
        }
        return VerificationResult.fail(telemetryPath, errors);
    }

    private static void expectString(final String json, final String key, final String expected, final List<String> errors) {
        final String actual = stringValue(json, key);
        if (!expected.equals(actual)) {
            errors.add(key + ": expected '" + expected + "', got '" + actual + "'");
        }
    }

    private static void expectTrue(final String json, final String key, final List<String> errors) {
        if (!booleanValue(json, key)) {
            errors.add(key + ": expected true");
        }
    }

    private static String stringValue(final String json, final String key) {
        final Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(null|\\\"((?:\\\\.|[^\\\"])*)\\\")");
        final Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return null;
        }
        if ("null".equals(matcher.group(1))) {
            return null;
        }
        return matcher.group(2).replace("\\\\", "\\").replace("\\\"", "\"");
    }

    private static boolean booleanValue(final String json, final String key) {
        final Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*true\\b");
        return pattern.matcher(json).find();
    }

    /**
     * telemetry 验收结果。
     *
     * @param passed        是否通过
     * @param telemetryPath telemetry 路径
     * @param errors        失败原因
     */
    public record VerificationResult(boolean passed,
                                     Path telemetryPath,
                                     List<String> errors) {
        /**
         * 创建通过结果。
         *
         * @param telemetryPath telemetry 路径
         * @return 通过结果
         */
        public static VerificationResult pass(final Path telemetryPath) {
            return new VerificationResult(true, telemetryPath, List.of());
        }

        /**
         * 创建失败结果。
         *
         * @param telemetryPath telemetry 路径
         * @param errors        失败原因
         * @return 失败结果
         */
        public static VerificationResult fail(final Path telemetryPath, final List<String> errors) {
            return new VerificationResult(false, telemetryPath, List.copyOf(errors));
        }
    }
}