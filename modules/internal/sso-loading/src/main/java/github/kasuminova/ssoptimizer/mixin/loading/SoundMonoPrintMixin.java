package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.logging.SoundMonoNoticeAggregator;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.PrintStream;

/**
 * Sound 构造器控制台刷屏的 Mixin 重定向。
 * <p>
 * 注入目标：{@code sound.Sound}<br>
 * 注入动机：{@code Sound(String, String, InputStream)} 构造器末尾两处直接
 * {@code System.out.println}（「UI sound [%s] is mono」/「Sound [%s] is NOT mono」），
 * 绕过 log4j 只刷控制台，加载几百个音效时逐条刷屏。<br>
 * 注入效果：两处 println 重定向到 {@link SoundMonoNoticeAggregator} 按分类计数，
 * 周期性输出汇总 INFO。两处调用点以 ordinal 区分（构造器内仅这两次
 * {@code PrintStream.println(String)} 调用，javap 核验）。
 */
@Mixin(targets = GameClassNames.SOUND_DOTTED)
public abstract class SoundMonoPrintMixin {
    /** 构造器内第一次 println（UI mono 提示）→ 聚合器。 */
    @Redirect(method = "<init>(Ljava/lang/String;Ljava/lang/String;Ljava/io/InputStream;)V",
            remap = false,
            at = @At(value = "INVOKE", ordinal = 0,
                    target = "Ljava/io/PrintStream;println(Ljava/lang/String;)V"))
    private void ssoptimizer$aggregateMonoNotice0(final PrintStream out, final String message) {
        SoundMonoNoticeAggregator.onConstructorPrint(message);
    }

    /** 构造器内第二次 println（NOT mono 提示）→ 聚合器。 */
    @Redirect(method = "<init>(Ljava/lang/String;Ljava/lang/String;Ljava/io/InputStream;)V",
            remap = false,
            at = @At(value = "INVOKE", ordinal = 1,
                    target = "Ljava/io/PrintStream;println(Ljava/lang/String;)V"))
    private void ssoptimizer$aggregateMonoNotice1(final PrintStream out, final String message) {
        SoundMonoNoticeAggregator.onConstructorPrint(message);
    }
}
