package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import com.fs.starfarer.combat.entities.ContrailEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 战役尾迹 {@code ContrailEngineV2.advance(float)} 的数组化等价实现（供
 * {@code ContrailEngineV2Mixin} 的 {@code @Overwrite advance} 委托）。
 * <p>
 * 与反编译原版（named 仓 {@code ContrailEngineV2.java:197-258}）逐行核对，
 * 与原版逐点公式<b>位级一致</b>地完成点更新，同时消除逐点冗余：
 * <ul>
 *   <li><b>组内恒量提升</b>：{@code remove}/{@code mode}/{@code widthMultiplier}
 *       原版每点重读组字段，advance 期间组字段无人改写（单线程、无旁路写入），
 *       提升到每组一次；{@code amount/3.0f} 与 {@code amount*2.0f} 原版每点
 *       重算，操作数恒定，提升到每次 advance 一次（位级同值）；</li>
 *   <li><b>死计算删除</b>：原版每组计算 {@code var5 = amount*0.25f} 与逐点
 *       {@code var6++} 计数，均仅写局部变量、不写任何字段，删除无可观察变化；</li>
 *   <li><b>progress 短路径</b>：原版每点恒执行 {@code elapsed/duration}；完全
 *       老化点（elapsed 已被钳制为 duration，稳态占点数大多数）按 IEEE 754
 *       x/x 恒为 1.0f，分支直接赋值省除法——duration == 0 时 0/0 仍走原除法
 *       保持 NaN 语义，位级一致。</li>
 *   <li><b>全老化组短路</b>：组内全部点处于「完全老化稳态」（条件见
 *       {@link #advanceContrail} 内注释，逐项保守）时逐点 advancePoint 的全部
 *       写回恒等，整组跳过逐点推进，只保留组级移除判定；</li>
 *   <li><b>removals 复用</b>：原版每帧 new ArrayList；本实现复用静态成员缓冲。
 *       advance 仅由游戏主线程经 {@code CampaignFleetView.advance} 调用（渲染
 *       线程只走 render 路径），无并发亦无重入（advance 不回调自身），普通字段
 *       即可。</li>
 * </ul>
 * 头段移除直接调用原版 {@code Contrail.removeFirstIfNecessary()}（含
 * totalLength 簿记与头点 maxBrightness 清零），不重复实现。
 */
public final class CampaignContrailAdvanceHelper {

    /**
     * removals 复用缓冲：每次 advance 开头清空。仅被游戏主线程调用，无并发；
     * 组内处理不回调 advance，无重入。
     */
    private static final List<Object> REMOVALS = new ArrayList<>();

    private CampaignContrailAdvanceHelper() {
    }

    /**
     * 整机尾迹推进：逐条点更新 + 过期头段移除 + remove/autoCleanup 尾迹清理。
     *
     * @param contrails 原版 {@code ContrailEngineV2.contrails}
     * @param amount    帧推进量（原版 advance(float) 的 var1）
     */
    public static void advance(Map<Object, ContrailEngineV2.Contrail> contrails, float amount) {
        List<Object> removals = REMOVALS;
        removals.clear();
        for (ContrailEngineV2.Contrail contrail : contrails.values()) {
            advanceContrail(contrail, amount, removals);
        }
        for (Object key : removals) {
            contrails.remove(key);
        }
    }

    static void advanceContrail(ContrailEngineV2.Contrail contrail, float amount, List<Object> removals) {
        List<ContrailEngineV2.ContrailPoint> points = contrail.points;
        boolean remove = contrail.remove;
        ContrailEngine.ContrailWidthMode mode = contrail.mode;
        float widthMultiplier = contrail.widthMultiplier;
        float removeExtraAge = amount / 3.0f;
        float fadeOutStep = amount * 2.0f;

        // 全老化短路（B2）：组内全部点处于完全老化稳态时，逐点 advancePoint 的
        // 全部写回恒等，跳过逐点推进，只保留下方组级移除判定。判定条件逐项保守
        // （allPointsAgedSteady 注释逐项论证），任一不满足即回退逐点路径；判定在
        // 首个非稳态点提前退出，活跃尾迹（尾部有未老化点）的额外开销以首个未
        // 老化点为界。
        boolean agedSteady = amount >= 0.0f
                && allPointsAgedSteady(points, remove, mode, widthMultiplier);
        if (!agedSteady) {
            // 索引遍历：points 运行期类型是 ArrayList（游戏构造器实例化），O(1) 随机访问
            int size = points.size();
            for (int i = 0; i < size; i++) {
                advancePoint(points.get(i), amount, removeExtraAge, fadeOutStep, remove, mode, widthMultiplier);
            }
        }

        // 原版组级逻辑：点数 >= 3 时循环移除已完全老化的头段；remove/autoCleanup
        // 且点数 < 3 且全部点完全老化时把尾迹加入移除清单（由调用方从 map 删除）。
        if (points.size() >= 3) {
            while (points.size() >= 3 && contrail.removeFirstIfNecessary()) {
                // 空循环体：移除动作由 removeFirstIfNecessary 完成
            }
        }
        if ((remove || contrail.autoCleanup) && points.size() < 3) {
            boolean allAged = true;
            for (int i = 0; i < points.size(); i++) {
                ContrailEngineV2.ContrailPoint point = points.get(i);
                if (point.elapsed < point.duration) {
                    allAged = false;
                }
            }
            if (allAged) {
                removals.add(contrail.source);
            }
        }
    }

    /**
     * 组内全部点是否处于「完全老化稳态」——即逐点 advancePoint 的每条写回都恒等，
     * 可以整组跳过。逐项条件与恒等论证（amount >= 0 由调用方保证）：
     * <ul>
     *   <li>{@code elapsed == duration && duration != 0}：elapsed 加 amount（及
     *       remove 态额外老化）后必被钳回 duration，写回同值；duration == 0 时
     *       progress 走 0/0=NaN 除法语义，不能由「恒写 1.0f」替代，排除；</li>
     *   <li>{@code progress == 1.0f}：advancePoint 会把 progress 重写为 1.0f
     *       （elapsed==duration 短路径），只有 progress 已是 1.0f 时写回恒等——
     *       排除反序列化截断（writeReplace 两位小数舍入）等外部写入造成的
     *       elapsed==duration 但 progress 未同步情形；</li>
     *   <li>{@code fadeOut && maxBrightness == 0.0f}：亮度衰减 {@code 0 - step}
     *       钳 0，写回同值。未 fadeOut 的点 maxBrightness 不衰减——虽写回同样
     *       恒等，但完全老化且未 fadeOut 的点在渲染侧仍有 fadeOut 检测价值，
     *       不作为本优化目标，保守排除（条件保持「fadeOut 完成」的字面语义）；</li>
     *   <li>width 已等于稳态值：WIDEN 稳态 {@code maxWidth*(widthMultiplier+1)}
     *       （progress==1 时代入原式，1.0f*x==x 精确成立）、NARROW 稳态
     *       {@code maxWidth*0.25f}（代入后 (1-progress) 项精确为 0）、其余模式
     *       原版不写 width 无约束——排除 updateContrail 改写 widthMultiplier
     *       后 width 滞后的情形；</li>
     *   <li>{@code !remove} 时 vel 为零向量：position 推进 {@code x + 0*amount}
     *       恒等（唯一位级差异是 x==-0.0f 时原版得 +0.0f，下游全部经由比较或
     *       平方运算消费，-0.0 与 +0.0 结论恒同，无可观察影响）。remove 态原版
     *       不推进 position，无约束。</li>
     * </ul>
     */
    private static boolean allPointsAgedSteady(List<ContrailEngineV2.ContrailPoint> points,
                                               boolean remove,
                                               ContrailEngine.ContrailWidthMode mode,
                                               float widthMultiplier) {
        int size = points.size();
        for (int i = 0; i < size; i++) {
            ContrailEngineV2.ContrailPoint point = points.get(i);
            float duration = point.duration;
            if (point.elapsed != duration || duration == 0.0f || point.progress != 1.0f) {
                return false;
            }
            // floatToRawIntBits 排除 -0.0f：原版衰减 0-step 后钳制恒写 +0.0f，
            // -0.0f 保留会产生位级差异（语义无影响，但保持严格一致代价为零）。
            if (!point.fadeOut || Float.floatToRawIntBits(point.maxBrightness) != 0) {
                return false;
            }
            if (mode == ContrailEngine.ContrailWidthMode.WIDEN) {
                if (point.width != point.maxWidth * (widthMultiplier + 1.0f)) {
                    return false;
                }
            } else if (mode == ContrailEngine.ContrailWidthMode.NARROW) {
                if (point.width != point.maxWidth * 0.25f) {
                    return false;
                }
            }
            if (!remove && (point.vel.x != 0.0f || point.vel.y != 0.0f)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 单点更新（原版逐点公式的等价实现，位级一致）。
     *
     * @param point          待更新点
     * @param amount         帧推进量
     * @param removeExtraAge remove 态额外老化量（amount/3，已提升的帧内恒量）
     * @param fadeOutStep    fadeOut 点亮度衰减步长（amount*2，已提升的帧内恒量）
     * @param remove         尾迹 remove 标志（已提升的组内恒量）
     * @param mode           尾迹宽度模式（已提升的组内恒量）
     * @param widthMultiplier 尾迹宽度倍率（已提升的组内恒量，仅 WIDEN 使用）
     */
    static void advancePoint(ContrailEngineV2.ContrailPoint point, float amount,
                             float removeExtraAge, float fadeOutStep, boolean remove,
                             ContrailEngine.ContrailWidthMode mode, float widthMultiplier) {
        point.elapsed += amount;
        if (remove) {
            point.elapsed += removeExtraAge;
        }
        if (point.elapsed > point.duration) {
            point.elapsed = point.duration;
        }

        // 原版恒执行 elapsed/duration；elapsed==duration 且 duration!=0 时 IEEE
        // x/x 恒为 1.0f，分支省去完全老化点的除法；duration==0 时仍走原除法
        // 保留 0/0=NaN。
        float duration = point.duration;
        point.progress = (point.elapsed == duration && duration != 0.0f)
                ? 1.0f
                : point.elapsed / duration;

        if (point.fadeOut) {
            point.maxBrightness -= fadeOutStep;
            if (point.maxBrightness < 0.0f) {
                point.maxBrightness = 0.0f;
            }
        }

        if (mode == ContrailEngine.ContrailWidthMode.WIDEN) {
            point.width = point.maxWidth * (point.progress * widthMultiplier + 1.0f);
        } else if (mode == ContrailEngine.ContrailWidthMode.NARROW) {
            point.width = point.maxWidth * (0.25f + (1.0f - point.progress) * 0.75f);
        }

        if (!remove) {
            point.point.x = point.point.x + point.vel.x * amount;
            point.point.y = point.point.y + point.vel.y * amount;
        }
    }
}
