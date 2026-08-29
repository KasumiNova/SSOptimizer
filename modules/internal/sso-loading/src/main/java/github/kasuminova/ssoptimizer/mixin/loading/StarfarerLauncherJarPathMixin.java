package github.kasuminova.ssoptimizer.mixin.loading;

import com.fs.starfarer.loading.scripts.ScriptStore;
import github.kasuminova.ssoptimizer.common.loading.CaseInsensitiveJarResolver;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * 模组 jar 声明路径大小写修复的注入层。
 * <p>
 * 注入目标：{@code com.fs.starfarer.StarfarerLauncher#launchGame(boolean, boolean, String, String)}
 * 中唯一的 {@code ScriptStore.getJarFiles()} 调用点（jar 列表回填循环）。<br>
 * 注入动机：{@code mod_info.json} 声明的 jar 文件名与磁盘实际文件可能存在仅大小写差异
 * （Windows 生态模组/汉化整合包），Linux 上该 jar 被静默跳过导致模组 Java 类整体缺失。
 * 原版在此处把「{@code 模组目录 + "/" + 声明 jar}」直接加入 jarFiles，无任何存在性校验。<br>
 * 注入效果：回填列表替换为 {@link CaseInsensitiveJarResolver#resolvingView} 的写入侧解析视图，
 * 精确命中的路径原样透传（零行为变化），大小写差异自动定位实际文件并 WARN，
 * 真缺失保持原路径走原版错误处理。jarFiles 是 jar 路径的唯一数据源
 * （脚本 URLClassLoader 与 NanoForge 兜底挂载均读取它），单点修正即覆盖全部消费方。<br>
 * 注入点唯一性由 sso-app 的 {@code StarfarerLauncherJarPathAnchorTest} 核验。
 */
@Mixin(targets = GameMixinSignatures.StarfarerLauncher.TARGET_CLASS, remap = false)
public abstract class StarfarerLauncherJarPathMixin {
    @Redirect(
            method = GameMixinSignatures.StarfarerLauncher.LAUNCH_GAME,
            at = @At(value = "INVOKE", target = GameMixinSignatures.StarfarerLauncher.GET_JAR_FILES_TARGET),
            remap = false)
    private static List<String> ssoptimizer$resolvingJarFiles() {
        return CaseInsensitiveJarResolver.resolvingView(ScriptStore.getJarFiles());
    }
}
