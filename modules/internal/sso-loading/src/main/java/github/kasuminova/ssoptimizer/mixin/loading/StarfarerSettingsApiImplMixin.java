package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.loading.ResourceLoaderThreadState;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * SettingsAPI 实现类中 suppressCustomResources 写入的 Mixin 重定向。
 * <p>
 * 注入目标：{@code com.fs.starfarer.settings.StarfarerSettings$SettingsTextFieldFactory}
 * （named 映射名；实际是 {@code StarfarerSettings} 内 {@code new SettingsAPI() {...}} 匿名
 * 实现类，loadCSV/loadJSON 等 API 方法都在其中）。<br>
 * 注入动机：{@code loadCSV(String, boolean)} / {@code loadJSON(String, boolean)} 在
 * {@code false} 分支直接 PUTSTATIC {@code ResourceLoader.suppressCustomResources = true}，
 * 该全局静态标记在并行加载下会泄漏到其他线程的 openResource（详见
 * {@link ResourceLoaderThreadState}）。<br>
 * 注入效果：两处 PUTSTATIC 重定向到线程本地写入；读取/消费侧由
 * {@link ResourceLoaderMixin} 配套重定向。两类指令锚点各自方法内唯一（javap 核验）。<br>
 * 共存说明：sso-ime 的 {@code SettingsTextFieldFactoryProcessor}（ASM）也改写本类，
 * 但只触及 {@code createTextField} 方法，与本 mixin 的 loadCSV/loadJSON 不相交，无重复改写。
 */
@Mixin(targets = GameClassNames.STARFARER_SETTINGS_API_IMPL_DOTTED)
public abstract class StarfarerSettingsApiImplMixin {
    /**
     * suppressCustomResources = true 写入重定向 → 线程本地。
     */
    @Redirect(method = {"loadCSV(Ljava/lang/String;Z)Lorg/json/JSONArray;",
            "loadJSON(Ljava/lang/String;Z)Lorg/json/JSONObject;"}, remap = false,
            at = @At(value = "FIELD", opcode = Opcodes.PUTSTATIC,
                    target = "Lcom/fs/util/ResourceLoader;suppressCustomResources:Z"))
    private static void ssoptimizer$setSuppressCustomResources(final boolean suppress) {
        ResourceLoaderThreadState.setSuppressCustomResources(suppress);
    }
}
