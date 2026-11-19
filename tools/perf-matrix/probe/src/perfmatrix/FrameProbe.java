package perfmatrix;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.combat.CombatState;
import com.fs.starfarer.title.Object.L;
import com.fs.starfarer.title.Object.Oo0O;
import com.fs.starfarer.title.TitleScreenState;
import com.fs.state.AppDriver;
import org.lwjgl.opengl.Display;

public final class FrameProbe extends BaseEveryFrameCombatPlugin {
    private static final String MISSION = "gl_benchmark";
    private static final long SECOND = 1_000_000_000L;
    private static boolean launched, stopped;
    private static long accepted;
    private long ready, start, last;
    private CombatEngineAPI engine;
    private final long[] stamps = new long[65536];
    private int frames;

    @Override public void init(CombatEngineAPI combat) {
        engine = combat;
        if (RunFiles.TOKEN == null || !launched || !MISSION.equals(engine.getMissionId())) return;
        if (engine.getCombatUI() instanceof CombatState) {
            ((CombatState) engine.getCombatUI()).setShowDeploymentDialogOnStart(false);
        }
    }

    @Override public void renderInUICoords(ViewportAPI viewport) {
        if (RunFiles.TOKEN == null || stopped) return;
        try {
            if (!launched && Global.getCurrentState() == GameState.TITLE) launch();
            else if (launched && Global.getCurrentState() == GameState.COMBAT) frame();
        } catch (Exception | LinkageError error) {
            stopped = true;
            Global.getLogger(FrameProbe.class).error("[PerfMatrix] benchmark failed", error);
            try {
                RunFiles.save("failure", error.toString());
                RunFiles.event("FAILED", System.nanoTime());
            } catch (Exception outputError) {
                Global.getLogger(FrameProbe.class).error("[PerfMatrix] failure report write failed", outputError);
            }
            System.exit(3);
        }
    }

    private static void launch() throws Exception {
        if (!(AppDriver.getInstance().getCurrentState() instanceof TitleScreenState)) return;
        TitleScreenState title = (TitleScreenState) AppDriver.getInstance().getCurrentState();
        if (title.getScreenPanel() == null) return;
        if (!RunFiles.TOKEN.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid SSO_PERF_RUN");
        RunFiles.event("TITLE_READY", System.nanoTime());
        RunFiles.metadata();
        Oo0O spec = new Oo0O(MISSION);
        spec.Ô00000("data.missions.gl_benchmark.MissionDefinition");
        spec.ô00000(Global.getSettings().loadJSON("data/missions/gl_benchmark/descriptor.json").getString("background"));
        L mission = new L(spec);
        for (FleetSide side : new FleetSide[]{FleetSide.PLAYER, FleetSide.ENEMY}) {
            int i = 0;
            for (FleetMemberAPI member : mission.getFleet(side).Object()) {
                RunFiles.member("initial_fleet", side.name(), i++, member.getVariant().getHullVariantId(), member.getDeploymentPointsCost());
            }
            if (i == 0) throw new IllegalStateException("Empty initial fleet: " + side);
        }
        RunFiles.saveRoster();
        launched = true;
        accepted = System.nanoTime();
        title.missionAccepted(mission);
    }

    private void frame() throws Exception {
        if (engine == null || !MISSION.equals(engine.getMissionId())) throw new IllegalStateException("Unexpected combat mission");
        long now = System.nanoTime();
        if (now - accepted >= 110 * SECOND) throw new IllegalStateException("Mission exceeded fixed-DP window");
        if (engine.isCombatOver()) throw new IllegalStateException("Mission ended during benchmark");
        if (engine.isUIShowingDialog() || engine.getCombatUI().isShowingCommandUI()) {
            if (ready != 0 || now - accepted >= 20 * SECOND) throw new IllegalStateException("Combat dialog blocked benchmark");
            return;
        }
        if (engine.isPaused()) {
            if (ready != 0) throw new IllegalStateException("Combat paused after warmup began");
            engine.setPaused(false);
            return;
        }
        if (!Display.isActive()) {
            if (ready != 0) throw new IllegalStateException("Display lost focus after warmup began");
            return;
        }
        if (ready == 0) {
            int[] counts = new int[2];
            for (ShipAPI ship : engine.getShips()) {
                if (ship.isAlive() && !ship.isFighter() && (ship.getOwner() == 0 || ship.getOwner() == 1)) counts[ship.getOwner()]++;
            }
            if (counts[0] == 0 || counts[1] == 0 || engine.getTotalElapsedTime(false) <= 0) return;
            int[] index = new int[2];
            for (ShipAPI ship : engine.getShips()) {
                int side = ship.getOwner();
                if (!ship.isAlive() || ship.isFighter() || side < 0 || side > 1) continue;
                RunFiles.member("first_deployed", side == 0 ? "PLAYER" : "ENEMY", index[side]++, ship.getVariant().getHullVariantId(),
                        ship.getFleetMember() == null ? 0 : ship.getFleetMember().getDeploymentPointsCost());
            }
            RunFiles.saveRoster();
            ready = System.nanoTime();
            RunFiles.event("WARMUP_BEGIN", ready);
        } else if (start == 0) {
            if (now - ready < 20 * SECOND) return;
            requireActiveFleets();
            start = last = System.nanoTime();
            RunFiles.remember("SAMPLE_BEGIN", start);
        } else {
            if (now <= last || frames == stamps.length) throw new IllegalStateException("Invalid frame clock or sample capacity");
            stamps[frames++] = last = now;
            if (now - start < 40 * SECOND) return;
            RunFiles.event("SAMPLE_END", now);
            requireActiveFleets();
            long previous = start;
            int part = 0;
            StringBuilder csv = new StringBuilder("frame,end_ns,dt_ns\n");
            for (int i = 0; i < frames; i++) {
                csv.append(i + 1).append(',').append(stamps[i]).append(',').append(stamps[i] - previous).append('\n');
                previous = stamps[i];
                if (csv.length() >= 990000 && i + 1 < frames) {
                    RunFiles.save(part == 0 ? "frames" : "frames_" + part, csv.toString());
                    part++;
                    csv.setLength(0);
                }
            }
            RunFiles.save(part == 0 ? "frames" : "frames_" + part, csv.toString());
            RunFiles.save("parts", Integer.toString(part + 1));
            stopped = true;
            RunFiles.event("DONE", System.nanoTime());
            System.exit(0);
        }
    }

    private void requireActiveFleets() {
        for (int side = 0; side < 2; side++) {
            if (engine.getFleetManager(side).getReservesCopy().isEmpty() || engine.getFleetManager(side).getTaskManager(false).isInFullRetreat()) {
                throw new IllegalStateException("GraphicsLib benchmark ended for side " + side);
            }
        }
    }
}
