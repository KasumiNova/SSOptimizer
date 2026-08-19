package github.kasuminova.ssoptimizer.common.automation;

import org.apache.log4j.Logger;

import java.lang.reflect.Method;

/**
 * 自动化 profile 的标题界面任务启动器。
 *
 * <p>Starsector 的普通 mission 入口由标题界面持有，{@code missionAccepted(...)} 是公开实例方法。
 * 该类在 Mixin hook 中通过标题界面的 mission widget 查找目标任务对象并调用该公开入口，
 * 从而进入 {@code data/missions/<id>/MissionDefinition} 定义的战斗。</p>
 */
public final class AutomationMissionLauncher {
    private static final Logger LOGGER = Logger.getLogger(AutomationMissionLauncher.class);

    private static volatile boolean launched;
    private static volatile boolean reportedMissing;
    private static volatile boolean reportedDiagnostics;

    private AutomationMissionLauncher() {
    }

    /**
     * 在标题界面 advance 尾部尝试启动配置的自动化 mission。
     *
     * @param titleScreenState 标题界面实例
     */
    public static void tryLaunchFromTitleScreen(final Object titleScreenState) {
        final AutomationConfig config = AutomationConfig.fromSystemProperties();
        if (!config.enabled() || launched || titleScreenState == null) {
            return;
        }

        final Object mission = selectMissionPreview(titleScreenState, config.scenario());
        if (mission == null) {
            if (!reportedMissing) {
                reportedMissing = true;
                LOGGER.warn("[SSO-Automation] mission not found on title screen: " + config.scenario());
            }
            return;
        }

        try {
            final Method missionAccepted = titleScreenState.getClass().getMethod("missionAccepted", mission.getClass());
            missionAccepted.invoke(titleScreenState, mission);
            launched = true;
            LOGGER.info("[SSO-Automation] launched mission from title screen: " + config.scenario());
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[SSO-Automation] failed to invoke missionAccepted for scenario=" + config.scenario(), e);
        }
    }

    private static Object selectMissionPreview(final Object titleScreenState, final String missionId) {
        final Object missionWidget = getMissionWidget(titleScreenState);
        if (missionWidget == null) {
            return null;
        }
        try {
            final Method getMissionList = missionWidget.getClass().getMethod("getMissionList");
            final Object missionList = getMissionList.invoke(missionWidget);
            if (missionList == null) {
                return null;
            }
            final Method selectMission = missionList.getClass().getMethod("selectMission", String.class);
            selectMission.invoke(missionList, missionId);

            final Method getMissionDetail = missionWidget.getClass().getMethod("getMissionDetail");
            final Object missionDetail = getMissionDetail.invoke(missionWidget);
            if (missionDetail == null) {
                return null;
            }
            final Method getPreview = missionDetail.getClass().getMethod("getPreview");
            final Object preview = getPreview.invoke(missionDetail);
            if (preview != null) {
                LOGGER.info("[SSO-Automation] selected title mission preview: " + missionId + " type=" + preview.getClass().getName());
                return preview;
            }
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[SSO-Automation] failed to select title mission: " + missionId, e);
        }
        return findMissionById(titleScreenState, missionId);
    }

    private static Object findMissionById(final Object titleScreenState, final String missionId) {
        final Object missionWidget = getMissionWidget(titleScreenState);
        if (missionWidget != null) {
            return findMissionInObjectGraph(missionWidget, missionId, 0);
        }
        return null;
    }

    private static Object getMissionWidget(final Object titleScreenState) {
        for (Class<?> type = titleScreenState.getClass(); type != null; type = type.getSuperclass()) {
            try {
                final var field = type.getDeclaredField("missionWidget");
                field.setAccessible(true);
                return field.get(titleScreenState);
            } catch (NoSuchFieldException ignored) {
                // Continue walking superclasses.
            } catch (IllegalAccessException e) {
                LOGGER.warn("[SSO-Automation] failed to inspect title mission widget", e);
                return null;
            }
        }
        return null;
    }

    private static Object findMissionInObjectGraph(final Object root, final String missionId, final int depth) {
        if (root == null || depth > 5) {
            return null;
        }
        if (missionId.equals(readStringId(root))) {
            return root;
        }
        logMissionDiagnostics(root, depth);
        final Class<?> type = root.getClass();
        if (type.getName().startsWith("java.")) {
            return null;
        }
        if (root instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                final Object found = findMissionInObjectGraph(item, missionId, depth + 1);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (type.isArray()) {
            final int length = java.lang.reflect.Array.getLength(root);
            for (int index = 0; index < length; index++) {
                final Object found = findMissionInObjectGraph(java.lang.reflect.Array.get(root, index), missionId, depth + 1);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (var field : current.getDeclaredFields()) {
                if (field.getType().isPrimitive() || field.getName().startsWith("$")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    final Object value = field.get(root);
                    final Object found = findMissionInObjectGraph(value, missionId, depth + 1);
                    if (found != null) {
                        return found;
                    }
                } catch (Throwable ignored) {
                    // Obfuscated UI graphs contain many inaccessible/runtime-only fields; skip them.
                }
            }
        }
        return null;
    }

    private static String readStringId(final Object candidate) {
        for (String methodName : new String[]{"getId", "getID", "getMissionId"}) {
            try {
                final Method method = candidate.getClass().getMethod(methodName);
                if (method.getReturnType() == String.class) {
                    return (String) method.invoke(candidate);
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        for (Class<?> current = candidate.getClass(); current != null; current = current.getSuperclass()) {
            for (var field : current.getDeclaredFields()) {
                if (field.getType() != String.class || field.getName().startsWith("$")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    final String value = (String) field.get(candidate);
                    if (value != null && !value.isBlank() && value.length() <= 128) {
                        return value;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static void logMissionDiagnostics(final Object candidate, final int depth) {
        if (reportedDiagnostics || depth > 4 || candidate == null) {
            return;
        }
        final String typeName = candidate.getClass().getName();
        if (!typeName.startsWith("com.fs.starfarer.title")) {
            return;
        }
        reportedDiagnostics = true;
        LOGGER.info("[SSO-Automation] inspected title mission graph node type=" + typeName);
        for (Class<?> current = candidate.getClass(); current != null; current = current.getSuperclass()) {
            for (var field : current.getDeclaredFields()) {
                if (field.getType() != String.class || field.getName().startsWith("$")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    final String value = (String) field.get(candidate);
                    LOGGER.info("[SSO-Automation] title mission string field " + current.getName() + "#" + field.getName() + "=" + value);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 重置测试状态。
     */
    public static void resetForTests() {
        launched = false;
        reportedMissing = false;
        reportedDiagnostics = false;
    }
}