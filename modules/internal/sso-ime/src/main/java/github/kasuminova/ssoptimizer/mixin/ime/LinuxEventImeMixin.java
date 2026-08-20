package github.kasuminova.ssoptimizer.mixin.ime;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Linux 事件层 IME 的 Mixin 重写。
 * <p>
 * 注入目标：{@code org.lwjgl.opengl.LinuxEvent#filterEvent(J)}<br>
 * 注入动机：原版 {@code filterEvent} 会调用 native {@code nFilterEvent}（即 {@code XFilterEvent}），
 * 使 IM 服务（如 fcitx）把每个事件看到两次——一次来自 LWJGL、一次来自 SSOptimizer 自身的 XIC，
 * 导致组字状态损坏。替换为直接返回 {@code false} 后，SSOptimizer 的原生代码成为 XFilterEvent 的唯一调用方。<br>
 * 注入效果：整个方法体替换为 {@code return false}（事件未被消费）。
 */
@Mixin(targets = GameClassNames.LINUX_EVENT_DOTTED)
public abstract class LinuxEventImeMixin {

    /**
     * 跳过 LWJGL 的 XFilterEvent 调用，声明事件未被消费。
     *
     * @param window 事件所属窗口（替换体不使用，保持签名一致）
     * @return 恒为 {@code false}
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 filterEvent(J)Z 方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    public boolean filterEvent(long window) {
        return false;
    }
}
