package github.kasuminova.ssoptimizer.common.render.engine;

import com.fs.starfarer.combat.entities.ContrailEngine;
import github.kasuminova.ssoptimizer.mixin.accessor.ContrailGroupAccessor;
import github.kasuminova.ssoptimizer.mixin.accessor.ContrailSegmentAccessor;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ContrailEngine {@code advance(float)} 的数组化等价实现（供
 * {@code ContrailEngineMixin} 的 {@code @Overwrite advance} 委托）。
 * <p>
 * v49 profile：ContrailEngine.advance 2,404 样本（4.9% 主线程），其中 LinkedList
 * 迭代与逐段更新数学并存。本实现与原版逐段公式<b>位级一致</b>地完成段更新，
 * 同时消除三处逐段冗余：
 * <ul>
 *   <li><b>组内恒量提升</b>：{@code ended}/{@code widthMode}/{@code widthMultiplier}
 *       原版每段重读组字段，advance 期间组字段无人改写（单线程、无旁路写入），
 *       提升到每组一次；</li>
 *   <li><b>死计算删除</b>：原版每段计算 f6/f7/f10 三个仅写局部变量的量
 *       （{@code f6 -= amount*0.25f; f7 += 1; f10 = 1 - progress*0.9f}），
 *       不写任何字段，删除无任何可观察状态变化；</li>
 *   <li><b>progress 短路径</b>：原版每段恒执行 {@code progress = texU/maxAge}；
 *       完全老化段（texU 已被钳制为 maxAge，稳态占段数大多数）按 IEEE 754
 *       x/x 恒为 1.0f，分支直接赋值省除法——maxAge == 0 时 0/0 仍走原除法
 *       保持 NaN 语义，位级一致。</li>
 * </ul>
 * 容器迭代按 {@link ContrailSegmentStore}（数组后备 List）索引进行，迭代序 =
 * 原 LinkedList 迭代序（段 0 = 最旧）。
 */
public final class ContrailAdvanceHelper {

    private ContrailAdvanceHelper() {
    }

    /**
     * 整机尾迹推进：逐组段更新 + 过期头段移除 + ended 组移除。
     *
     * @param groups 原版 {@code ContrailEngine.groups}（Map<Object, ContrailGroup>）
     * @param amount 帧推进量（原版 advance(float) 的 amount）
     */
    public static void advance(Map<?, ?> groups, float amount) {
        List<Object> removals = new ArrayList<>();
        for (Object groupObject : groups.values()) {
            ContrailGroupAccessor group = (ContrailGroupAccessor) groupObject;
            advanceGroup(group, amount, removals);
        }
        for (Object key : removals) {
            groups.remove(key);
        }
    }

    private static void advanceGroup(ContrailGroupAccessor group, float amount, List<Object> removals) {
        List<Object> segments = group.ssoptimizer$getSegments();
        boolean ended = group.ssoptimizer$getEnded();
        ContrailEngine.ContrailWidthMode widthMode = group.ssoptimizer$getWidthMode();
        float widthMultiplier = group.ssoptimizer$getWidthMultiplier();

        for (int i = 0; i < segments.size(); i++) {
            advanceSegment((ContrailSegmentAccessor) segments.get(i), amount, ended, widthMode, widthMultiplier);
        }

        // 原版组级逻辑：段数 >= 2 时循环移除已完全老化的头段；段数 < 2 且组已
        // ended 时把组加入移除清单（由调用方从 groups 中删除）。
        if (segments.size() >= 2) {
            while (segments.size() >= 2 && group.ssoptimizer$removeExpiredSegment()) {
                // 空循环体：移除动作由 removeExpiredSegment 完成
            }
        } else if (ended) {
            removals.add(group.ssoptimizer$getKey());
        }
    }

    /**
     * 单段更新（原版逐段公式的等价实现，位级一致）。
     *
     * @param segment        待更新段
     * @param amount         帧推进量
     * @param ended          组 ended 标志（已提升的组内恒量）
     * @param widthMode      组宽度模式（已提升的组内恒量）
     * @param widthMultiplier 组宽度倍率（已提升的组内恒量，仅 WIDEN 使用）
     */
    static void advanceSegment(ContrailSegmentAccessor segment, float amount, boolean ended,
                               ContrailEngine.ContrailWidthMode widthMode, float widthMultiplier) {
        float texU = segment.ssoptimizer$getU();
        texU += amount;
        if (ended) {
            texU += amount / 3.0f;
        }
        float maxAge = segment.ssoptimizer$getMaxAge();
        if (texU > maxAge) {
            texU = maxAge;
        }
        segment.ssoptimizer$setTexU(texU);

        // 原版恒执行 texU/maxAge；texU==maxAge 且 maxAge!=0 时 IEEE x/x 恒为
        // 1.0f，分支省去完全老化段的除法；maxAge==0 时仍走原除法保留 0/0=NaN。
        float progress = (texU == maxAge && maxAge != 0.0f) ? 1.0f : texU / maxAge;
        segment.ssoptimizer$setProgress(progress);

        if (widthMode == ContrailEngine.ContrailWidthMode.WIDEN) {
            segment.ssoptimizer$setWidth(segment.ssoptimizer$getBaseWidth()
                    * (progress * widthMultiplier + 1.0f));
        } else if (widthMode == ContrailEngine.ContrailWidthMode.NARROW) {
            segment.ssoptimizer$setWidth(segment.ssoptimizer$getBaseWidth()
                    * (0.25f + (1.0f - progress) * 0.75f));
        }

        if (!ended) {
            // 原版顺序：先 vel.scale(1-progress)，再用缩放后的 vel 推进 position
            Vector2f vel = segment.ssoptimizer$getVel();
            vel.scale(1.0f - progress);
            Vector2f position = segment.ssoptimizer$getPosition();
            position.x += vel.x * amount;
            position.y += vel.y * amount;
        }
    }
}
