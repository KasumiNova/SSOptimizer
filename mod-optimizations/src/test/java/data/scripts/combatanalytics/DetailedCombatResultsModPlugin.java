package data.scripts.combatanalytics;

import data.scripts.combatanalytics.data.CombatResult;
import github.kasuminova.ssoptimizer.modopt.dcr.TestModPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试夹具：模拟 DCR 的 {@code DetailedCombatResultsModPlugin}（内部名同名），其 {@code onGameLoad(boolean)}
 * 含一个「清空 + 逐条 saveCombatResult」的 trim 重写循环（带循环帧），供 {@code DcrOnGameLoadProcessor}
 * redirect 该调用为 {@code ssoptimizer$collect} 并在 return 前 inject {@code ssoptimizer$flush}。
 */
public class DetailedCombatResultsModPlugin implements TestModPlugin {

    private final int itemCount;

    public DetailedCombatResultsModPlugin() {
        this(5);
    }

    public DetailedCombatResultsModPlugin(final int itemCount) {
        this.itemCount = itemCount;
    }

    @Override
    public void onGameLoad(final boolean newGame) {
        SerializationManager.clearSavedData();
        final List<CombatResult> items = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            items.add(new CombatResult("id-" + i));
        }
        for (final CombatResult cr : items) {
            SerializationManager.saveCombatResult(cr);
        }
    }

    @Override
    public int cacheSize() {
        return SerializationManager.getAllSavedCombatResults().size();
    }
}
