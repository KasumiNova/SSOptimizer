package data.scripts.combatanalytics;

import com.thoughtworks.xstream.XStream;
import data.scripts.combatanalytics.data.CombatResult;
import github.kasuminova.ssoptimizer.modopt.dcr.TestProbe;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 测试夹具：模拟 DCR 的 {@code SerializationManager}（内部名 {@code data/scripts/combatanalytics/SerializationManager}），
 * 提供合成 collect/flush 注入后会调用到的方法签名：{@code getAllSavedCombatResults()/getSerializer()/saveValue()}，
 * 以及 {@code _resultCache} 字段与镜像 DCR 语义的 {@code clearSavedData()}（把 {@code _resultCache} 置 null）。
 * <p>
 * {@code saveValue} 通过 {@link TestProbe} 记录「存盘次数」，用于断言逐条 N 次 → 合并 1 次。
 */
public class SerializationManager {

    private static final String COMBATRESULTS_KEY = "CombatAnalytics_CombatResults_V4";

    // 镜像真实 DCR 的字段：合成 flush 的 catch 分支会 getstatic 此 log 字段。
    private static final Logger log = Logger.getLogger(SerializationManager.class);

    private static List<CombatResult> _resultCache;

    public static List<CombatResult> getAllSavedCombatResults() {
        if (_resultCache == null) {
            _resultCache = new ArrayList<>();
        }
        return _resultCache;
    }

    /** 镜像 DCR：clearCache 把 {@code _resultCache} 置 null（验证 collect 重建、flush 读重建后的缓存）。 */
    public static void clearSavedData() {
        _resultCache = null;
    }

    /** 逐条全量存：基线路径（被 {@code DcrOnGameLoadProcessor} redirect 后不再走这里）。 */
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
