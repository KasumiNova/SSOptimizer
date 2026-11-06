package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.IOException;
import java.io.InputStream;

@Mixin(targets = GameMixinSignatures.SoundManager.TARGET_CLASS)
public interface SoundManagerAccessor {
    @Invoker(value = GameMemberNames.SoundManager.LOAD_OBJECT_FAMILY_FROM_STREAM, remap = false)
    sound.Audio ssoptimizer$loadObjectFamilyFromStream(String resourcePath,
                                                       InputStream inputStream) throws IOException;

    @Invoker(value = GameMemberNames.SoundManager.LOAD_O00000_FAMILY_FROM_STREAM, remap = false)
    sound.Audio ssoptimizer$loadO00000FamilyFromStream(String resourcePath,
                                                       InputStream inputStream) throws IOException;

    @Invoker(value = GameMemberNames.SoundManager.LOAD_O_ACCENT_FAMILY_FROM_STREAM, remap = false)
    sound.Audio ssoptimizer$loadOAccentFamilyFromStream(String resourcePath,
                                                        InputStream inputStream) throws IOException;
}
