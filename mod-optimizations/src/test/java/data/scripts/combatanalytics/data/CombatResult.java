package data.scripts.combatanalytics.data;

/**
 * 测试夹具：模拟 DCR 的 {@code CombatResult}（内部名 {@code data/scripts/combatanalytics/data/CombatResult}）。
 * 仅需可 XStream 序列化的 POJO + {@link Comparable}（合成 flush 会 {@code Collections.sort}）。
 */
public class CombatResult implements Comparable<CombatResult> {

    public String combatId;

    public CombatResult() {
    }

    public CombatResult(final String combatId) {
        this.combatId = combatId;
    }

    @Override
    public int compareTo(final CombatResult other) {
        return this.combatId.compareTo(other.combatId);
    }
}
