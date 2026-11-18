import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.TreeSet;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordingFile;

/** 检查同一 JVM 录制中的状态帧及战役序列化调用。 */
final class RecordingCheck {
    private enum Stage { TITLE, NEW_GAME, LOAD_GAME, CAMPAIGN, COMBAT }

    private static Stage stage(RecordedMethod method) {
        String name = method.getName();
        return switch (method.getType().getName()) {
            case "com.fs.starfarer.title.TitleScreenState" ->
                    name.equals("render") || name.equals("advance") ? Stage.TITLE : null;
            case "com.fs.starfarer.combat.CombatState" -> name.equals("traverse") ? Stage.COMBAT : null;
            case "com.fs.starfarer.campaign.CampaignState" ->
                    name.equals("render") || name.equals("advance") ? Stage.CAMPAIGN : null;
            case "com.fs.starfarer.campaign.save.CampaignGameManager" -> {
                String desc = method.getDescriptor();
                if (name.equals("newGame") || (name.equals("o00000") && desc.equals(
                        "(Lcom/fs/starfarer/campaign/save/return;Lcom/fs/starfarer/campaign/CampaignState;)Ljava/lang/String;"))) {
                    yield Stage.NEW_GAME;
                }
                if (name.equals("loadGame") || (name.equals("o00000") && desc.startsWith(
                        "(Ljava/lang/String;Lcom/fs/starfarer/campaign/void;Lcom/fs/starfarer/campaign/CampaignEngine$o;"))) {
                    yield Stage.LOAD_GAME;
                }
                yield null;
            }
            default -> null;
        };
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("用法：RecordingCheck <JFR> <title|new|load|combat>");
        Stage[] required = switch (args[1]) {
            case "title" -> new Stage[]{Stage.TITLE};
            case "combat" -> new Stage[]{Stage.TITLE, Stage.COMBAT};
            case "new" -> new Stage[]{Stage.TITLE, Stage.NEW_GAME, Stage.CAMPAIGN};
            case "load" -> new Stage[]{Stage.TITLE, Stage.LOAD_GAME, Stage.CAMPAIGN};
            default -> throw new IllegalArgumentException("未知场景：" + args[1]);
        };
        var times = new EnumMap<Stage, TreeSet<Instant>>(Stage.class);
        try (var recording = new RecordingFile(Path.of(args[0]))) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                if (!(type.equals("jdk.ExecutionSample") || type.equals("jdk.NativeMethodSample")) || event.getStackTrace() == null) continue;
                Instant time = event.getStartTime();
                for (RecordedFrame frame : event.getStackTrace().getFrames()) {
                    if (!frame.isJavaFrame()) continue;
                    Stage s = stage(frame.getMethod());
                    if (s == null) continue;
                    times.computeIfAbsent(s, ignored -> new TreeSet<>()).add(time);
                }
            }
        }
        times.forEach((stage, samples) -> System.out.printf("%s  %s .. %s%n", stage, samples.first(), samples.last()));
        Instant previous = Instant.MIN;
        for (Stage s : required) {
            if (!times.containsKey(s) || (previous = times.get(s).higher(previous)) == null) {
                throw new IllegalStateException("录制缺少按顺序执行的游戏帧：" + s);
            }
        }
    }
}
