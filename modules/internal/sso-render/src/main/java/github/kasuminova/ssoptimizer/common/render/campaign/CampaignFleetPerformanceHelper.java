package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import com.fs.starfarer.util.ColorShifter;
import com.fs.starfarer.util.ValueShifter;

import java.awt.*;
import java.util.Map;
import java.util.Objects;

/**
 * 战役舰队视图热点的轻量短路 helper。
 * <p>
 * 职责：为战役层舰队成员视图与尾迹系统提供“仅在状态活跃时才推进/渲染”的统一判定，
 * 避免在无任何视觉过渡或尾迹数据时继续执行空转逻辑。<br>
 * 设计动机：热点报告显示 {@code CampaignFleetMemberView.advance()} 与
 * {@code ContrailEngineV2.advance/render()} 在战役场景中占据明显 CPU 时间，
 * 其中一部分来自空状态下的重复推进与集合分配。<br>
 * 效果：跳过未激活 shifter 的无效推进，以及空尾迹集合的推进/渲染调用，
 * 在不改变视觉结果的前提下降低战役场景的每帧开销。
 */
public final class CampaignFleetPerformanceHelper {
    private CampaignFleetPerformanceHelper() {
    }

    /**
     * 仅在颜色偏移器仍有活动过渡，或当前值尚未回到基准值时推进其状态。
     *
     * @param shifter 颜色偏移器
     * @param amount  推进时间步长
     */
    public static void advanceColorShifterIfNeeded(final ColorShifter shifter,
                                                   final float amount) {
        if (shifter == null) {
            return;
        }
        if (!shifter.isShifted() && Objects.equals(currentColor(shifter), shifter.getBase())) {
            return;
        }
        shifter.advance(amount);
    }

    /**
     * 仅在数值偏移器仍有活动过渡，或当前值尚未回到基准值时推进其状态。
     *
     * @param shifter 数值偏移器
     * @param amount  推进时间步长
     */
    public static void advanceValueShifterIfNeeded(final ValueShifter shifter,
                                                   final float amount) {
        if (shifter == null) {
            return;
        }
        if (!shifter.isShifted() && Float.compare(shifter.getCurr(), shifter.getBase()) == 0) {
            return;
        }
        shifter.advance(amount);
    }

    /**
     * 仅在存在活动尾迹时推进尾迹状态。
     *
     * @param contrails 战役舰队尾迹引擎
     * @param amount    推进时间步长
     */
    public static void advanceContrailsIfNeeded(final ContrailEngineV2 contrails,
                                                final float amount) {
        if (!hasActiveContrails(contrails)) {
            return;
        }
        contrails.advance(amount);
    }

    /**
     * 仅在存在活动尾迹时渲染尾迹。
     *
     * @param contrails 战役舰队尾迹引擎
     * @param alphaMult 渲染透明度倍率
     */
    public static void renderContrailsIfNeeded(final ContrailEngineV2 contrails,
                                               final float alphaMult) {
        if (!hasActiveContrails(contrails)) {
            return;
        }
        contrails.render(alphaMult);
    }

    /**
     * 判断尾迹引擎当前是否持有任何活动尾迹。
     *
     * @param contrails 战役舰队尾迹引擎
     * @return 若存在至少一个尾迹条目则返回 {@code true}
     */
    public static boolean hasActiveContrails(final ContrailEngineV2 contrails) {
        if (contrails == null) {
            return false;
        }

        final Map<?, ?> activeContrails = contrails.getContrails();
        return activeContrails != null && !activeContrails.isEmpty();
    }

    private static Color currentColor(final ColorShifter shifter) {
        final Color current = shifter.getCurr();
        return current != null ? current : shifter.getBase();
    }
}