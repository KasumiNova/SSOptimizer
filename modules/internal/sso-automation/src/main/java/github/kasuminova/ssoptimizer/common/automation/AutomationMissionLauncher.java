package github.kasuminova.ssoptimizer.common.automation;

import com.fs.starfarer.title.MissionDefinition;
import com.fs.starfarer.title.MissionWidget;
import com.fs.starfarer.title.TitleScreenState;
import org.apache.log4j.Logger;

public final class AutomationMissionLauncher {
    private static final Logger LOGGER = Logger.getLogger(AutomationMissionLauncher.class);

    private static volatile boolean launched;
    private static volatile boolean reportedMissing;
    private static volatile boolean failed;

    private AutomationMissionLauncher() {
    }

    public static void tryLaunchFromTitleScreen(final TitleScreenState titleScreenState,
                                                final MissionWidget missionWidget) {
        final AutomationConfig config = AutomationConfig.fromSystemProperties();
        if (!config.enabled() || config.scenario().isBlank() || launched || failed || titleScreenState == null || missionWidget == null) {
            return;
        }

        try {
            final MissionDefinition mission = selectMissionPreview(missionWidget, config.scenario());
            if (mission == null) {
                if (!reportedMissing) {
                    reportedMissing = true;
                    LOGGER.warn("[SSO-Automation] mission not found on title screen: " + config.scenario());
                }
                return;
            }

            titleScreenState.missionAccepted(mission);
        } catch (final RuntimeException e) {
            failed = true;
            LOGGER.error("[SSO-Automation] mission launch from title screen failed, automation stops retrying: "
                    + config.scenario(), e);
            return;
        }
        launched = true;
        LOGGER.info("[SSO-Automation] launched mission from title screen: " + config.scenario());
    }

    private static MissionDefinition selectMissionPreview(final MissionWidget missionWidget, final String missionId) {
        missionWidget.getMissionList().selectMission(missionId);
        final MissionDefinition preview = missionWidget.getMissionDetail().getPreview();
        if (preview == null || !missionId.equals(preview.getSpec().getId())) {
            return null;
        }
        LOGGER.info("[SSO-Automation] selected title mission preview: " + missionId);
        return preview;
    }

    public static void resetForTests() {
        launched = false;
        reportedMissing = false;
    }
}