package github.kasuminova.ssoptimizer.common.combat.ai;

/**
 * 战机编队分组键提供者。
 * <p>
 * 由 ASM 处理器织入 {@code com.fs.starfarer.combat.ai.FighterAI}（源码中不存在 implements 关系），
 * 用于并行 AI 调度时获取编队分组键：同一 {@code FighterWing} 的战机共享 wing 对象，
 * 其 AI 必须串行执行，调度器以此键将同编队任务固定到同一工作线程。
 */
public interface WingKeyProvider {
    /**
     * 返回编队分组键（通常为 {@code FighterWing} 实例），仅用于身份比较（identity hash）。
     *
     * @return 分组键，无编队语义时可为 {@code null}
     */
    Object ssoptimizer$getWingKey();
}
