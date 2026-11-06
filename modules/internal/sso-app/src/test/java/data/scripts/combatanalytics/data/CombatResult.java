package data.scripts.combatanalytics.data;

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
