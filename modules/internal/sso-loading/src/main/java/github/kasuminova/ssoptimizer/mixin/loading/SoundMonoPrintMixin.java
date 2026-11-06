package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.logging.SoundMonoNoticeAggregator;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.PrintStream;

@Mixin(targets = GameClassNames.SOUND_DOTTED)
public abstract class SoundMonoPrintMixin {
    @Redirect(method = "<init>(Ljava/lang/String;Ljava/lang/String;Ljava/io/InputStream;)V",
            remap = false,
            at = @At(value = "INVOKE", ordinal = 0,
                    target = "Ljava/io/PrintStream;println(Ljava/lang/String;)V"))
    private void ssoptimizer$aggregateMonoNotice0(final PrintStream out, final String message) {
        SoundMonoNoticeAggregator.onConstructorPrint(message);
    }

    @Redirect(method = "<init>(Ljava/lang/String;Ljava/lang/String;Ljava/io/InputStream;)V",
            remap = false,
            at = @At(value = "INVOKE", ordinal = 1,
                    target = "Ljava/io/PrintStream;println(Ljava/lang/String;)V"))
    private void ssoptimizer$aggregateMonoNotice1(final PrintStream out, final String message) {
        SoundMonoNoticeAggregator.onConstructorPrint(message);
    }
}
