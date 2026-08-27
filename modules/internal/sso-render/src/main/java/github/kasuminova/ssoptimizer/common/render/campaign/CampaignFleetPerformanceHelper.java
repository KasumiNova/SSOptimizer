package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import com.fs.starfarer.util.ColorShifter;
import com.fs.starfarer.util.ValueShifter;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.util.Map;
import java.util.Objects;

/**
 * 战役舰队视图热点的轻量短路 helper。
 * <p>
 * 职责：为战役层舰队成员视图与尾迹系统提供“仅在状态活跃时才推进/渲染”的统一判定，
 * 避免在无任何视觉过渡或尾迹数据时继续执行空转逻辑；同时为战役尾迹提供视口距离 LOD
 * 与单条尾迹点数上限。<br>
 * 设计动机：热点报告显示 {@code CampaignFleetMemberView.advance()} 与
 * {@code ContrailEngineV2.advance/render()} 在战役场景中占据明显 CPU 时间，
 * 其中一部分来自空状态下的重复推进与集合分配；另一部分来自视口外舰队的尾迹渲染，
 * 以及 hyperspace/冲刺场景下单条尾迹点数的无界膨胀（原版只增点、无上限）。<br>
 * 效果：跳过未激活 shifter 的无效推进、空尾迹集合的推进/渲染调用、视口外舰队的
 * 整条尾迹渲染，并给单条尾迹点数设上限；在不改变可见视觉结果的前提下降低战役场景的
 * 每帧开销。
 */
public final class CampaignFleetPerformanceHelper {
    /**
     * 尾迹视口距离 LOD 总开关（{@code -Dssoptimizer.render.contrail.lod}，默认开启）。
     * 关闭后视口外舰队的尾迹照常渲染（回退原版行为）。
     */
    public static final boolean CONTRAIL_LOD_ENABLED =
            Boolean.parseBoolean(System.getProperty("ssoptimizer.render.contrail.lod", "true"));

    /**
     * 尾迹 LOD 视口外扩边距（{@code -Dssoptimizer.render.contrail.lod.margin}，默认 3000）。
     * 舰队位置超出可视区域该边距时整条尾迹跳过渲染；默认值远大于尾迹实际长度
     * （duration 0.5~2.0s × 舰队速度，数百单位量级），屏幕边缘不会出现可见截断。
     */
    public static final float CONTRAIL_LOD_MARGIN =
            Float.parseFloat(System.getProperty("ssoptimizer.render.contrail.lod.margin", "3000"));

    /**
     * 单条尾迹点数上限（{@code -Dssoptimizer.render.contrail.maxpoints}，默认 256，&le;0 关闭上限）。
     * 稳态尾迹点数约 duration × 补点速率（百级），上限仅约束 hyperspace/冲刺等
     * 超长帧补点导致的膨胀；超限后新点被丢弃，旧点照常老化移除，尾迹长度随之收敛。
     */
    public static final int CONTRAIL_MAX_POINTS =
            Integer.parseInt(System.getProperty("ssoptimizer.render.contrail.maxpoints", "256"));

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
     * 仅在存在活动尾迹且舰队位于视口 LOD 范围内时渲染尾迹。
     *
     * @param contrails     战役舰队尾迹引擎
     * @param alphaMult     渲染透明度倍率
     * @param fleetLocation 舰队当前位置（尾迹附着点）
     * @param viewport      当前战役视口
     */
    public static void renderContrailsIfNeeded(final ContrailEngineV2 contrails,
                                               final float alphaMult,
                                               final Vector2f fleetLocation,
                                               final ViewportAPI viewport) {
        if (!hasActiveContrails(contrails)) {
            return;
        }
        if (CONTRAIL_LOD_ENABLED && !viewport.isNearViewport(fleetLocation, CONTRAIL_LOD_MARGIN)) {
            return;
        }
        contrails.render(alphaMult);
    }

    /**
     * 判断单条尾迹是否已达到点数上限。
     *
     * @param contrail  尾迹条目；{@code null}（未知来源）不拦截，保持原版空操作语义
     * @param maxPoints 点数上限；&le;0 表示不启用上限
     * @return 若该尾迹不应再继续补点则返回 {@code true}
     */
    public static boolean isContrailPointCapReached(final ContrailEngineV2.Contrail contrail,
                                                    final int maxPoints) {
        return maxPoints > 0 && contrail != null && contrail.points.size() >= maxPoints;
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
