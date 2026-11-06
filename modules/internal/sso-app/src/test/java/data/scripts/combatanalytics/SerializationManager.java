package data.scripts.combatanalytics;

import com.thoughtworks.xstream.XStream;
import data.scripts.combatanalytics.data.CombatResult;
import github.kasuminova.ssoptimizer.modopt.dcr.TestProbe;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SerializationManager {

    private static final String COMBATRESULTS_KEY = "CombatAnalytics_CombatResults_V4";

    private static final Logger log = Logger.getLogger(SerializationManager.class);

    private static List<CombatResult> _resultCache;

    public static List<CombatResult> getAllSavedCombatResults() {
        if (_resultCache == null) {
            _resultCache = new ArrayList<>();
        }
        return _resultCache;
    }

    public static void clearSavedData() {
        _resultCache = null;
    }

    public static void saveCombatResult(final CombatResult cr) {
        final List<CombatResult> list = getAllSavedCombatResults();
        list.add(cr);
        Collections.sort(list);
        saveValue(COMBATRESULTS_KEY, getSerializer().toXML(list));
    }

    private static void saveValue(final String key, final String value) {
        if (TestProbe.failSaves) {
            throw new RuntimeException("fixture saveValue failure (testing flush try/catch)");
        }
        TestProbe.recordSave(value);
    }

    private static XStream getSerializer() {
        return new XStream();
    }
}
