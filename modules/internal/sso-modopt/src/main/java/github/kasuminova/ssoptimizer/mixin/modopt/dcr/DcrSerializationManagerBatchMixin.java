package github.kasuminova.ssoptimizer.mixin.modopt.dcr;

import com.thoughtworks.xstream.XStream;
import github.kasuminova.ssoptimizer.modopt.dcr.DcrBatchSaveWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 在批量保存窗口内跳过 {@code saveCombatResult} 的中间序列化与落盘。
 * <p>列表追加与排序保持原样，窗口内最后一次保存沿用原方法完整路径，
 * 包括异常捕获与错误提示，因此末态与逐条保存一致。被跳过的保存以 {@code null} 序列化结果标记，
 * 真实的 {@code toXML} 不会返回 {@code null}。</p>
 */
@Mixin(targets = "data.scripts.combatanalytics.SerializationManager")
public abstract class DcrSerializationManagerBatchMixin {

    @Shadow(remap = false)
    private static void saveValue(final String key, final String value) {
        throw new AssertionError();
    }

    @Redirect(method = "saveCombatResult", remap = false, expect = 1,
            at = @At(value = "INVOKE", target = "Lcom/thoughtworks/xstream/XStream;toXML(Ljava/lang/Object;)Ljava/lang/String;", remap = false))
    private static String ssoptimizer$deferToXml(final XStream serializer, final Object results) {
        return DcrBatchSaveWindow.deferCurrentSave() ? null : serializer.toXML(results);
    }

    @Redirect(method = "saveCombatResult", remap = false, expect = 1,
            at = @At(value = "INVOKE", target = "Ldata/scripts/combatanalytics/SerializationManager;saveValue(Ljava/lang/String;Ljava/lang/String;)V", remap = false))
    private static void ssoptimizer$saveUnlessDeferred(final String key, final String value) {
        if (value != null) {
            saveValue(key, value);
        }
    }
}
