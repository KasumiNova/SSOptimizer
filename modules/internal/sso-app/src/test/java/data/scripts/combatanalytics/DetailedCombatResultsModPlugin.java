package data.scripts.combatanalytics;

import data.scripts.combatanalytics.data.CombatResult;
import github.kasuminova.ssoptimizer.modopt.dcr.TestModPlugin;

import java.util.ArrayList;
import java.util.List;

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
