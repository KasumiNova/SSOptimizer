package github.kasuminova.ssoptimizer.mapping;

/**
 * Mixin 运行时签名常量表。
 * <p>
 * 职责：为必须使用编译期常量的 {@code @Mixin(targets=...)}、{@code @Inject(method=...)}、
 * {@code @Shadow(aliases=...)} 等注解参数提供统一入口，避免在 {@code app} 模块中继续散落混淆类名、
 * 字段名和方法描述符字面量。<br>
 * 设计动机：{@link GameClassNames} / {@link GameMemberNames} 提供成员名常量，但注解参数要求编译期常量，
 * 因此集中维护一份 Mixin 签名桥接表（成员名与描述符拼接为完整签名）。<br>
 * 兼容性策略：所有常量都必须与 SourceSector 全量表（named 命名空间）保持语义一致；
 * 若运行时签名变化，必须同时更新 SourceSector 映射表、该常量表和相应测试。
 */
public final class GameMixinSignatures {
    private GameMixinSignatures() {
    }

    /**
     * 保存进度对话框 Mixin 签名常量。
     */
    public static final class CampaignSaveProgressDialog {
        public static final String TARGET_CLASS = "com.fs.starfarer.campaign.save.CampaignSaveProgressDialog";
        public static final String STRING_CONSTRUCTOR = "<init>(Ljava/lang/String;)V";
        public static final String REPORT_PROGRESS_WITH_TEXT = "reportProgress(Ljava/lang/String;F)V";
        public static final String REPORT_PROGRESS = "reportProgress(F)V";

        private CampaignSaveProgressDialog() {
        }
    }

    /**
     * 保存进度输出流 Mixin 签名常量。
     */
    public static final class SaveProgressOutputStream {
        public static final String TARGET_CLASS = "com.fs.starfarer.util.SaveProgressOutputStream";
        public static final String PROGRESS_CONSTRUCTOR = "<init>(Ljava/io/OutputStream;JFFLcom/fs/starfarer/campaign/save/CampaignSaveProgressDialog;)V";
        public static final String WRITE_BYTES = "write([BII)V";
        public static final String CLOSE = "close()V";
        public static final String WRITTEN_BYTES_FIELD = "writtenBytes";

        private SaveProgressOutputStream() {
        }
    }

    /**
     * 保存进度输入流（读档进度）Mixin 签名常量。
     */
    public static final class SaveProgressInputStream {
        public static final String TARGET_CLASS = "com.fs.starfarer.util.SaveProgressInputStream";
        public static final String UPDATE_PROGRESS = "updateProgress(Z)V";
        public static final String MARK_COMPLETE = "markComplete()V";

        private SaveProgressInputStream() {
        }
    }

    /**
     * 战役存档管理器 Mixin 签名常量。
     */
    public static final class CampaignGameManager {
        public static final String TARGET_CLASS = "com.fs.starfarer.campaign.save.CampaignGameManager";
        /** 五参完整读档入口（注意 CampaignUI 是 CampaignEngine 的内部接口）。 */
        public static final String LOAD_GAME = "loadGame(Ljava/lang/String;Lcom/fs/starfarer/campaign/CampaignListener;Lcom/fs/starfarer/campaign/CampaignEngine$CampaignUI;ZZ)Ljava/lang/String;";
        /** 读档后模组插件回调调用点（阶段33 的 onGameLoad(false) 循环）。 */
        public static final String MOD_PLUGIN_ON_GAME_LOAD = "Lcom/fs/starfarer/api/ModPlugin;onGameLoad(Z)V";

        private CampaignGameManager() {
        }
    }

    /**
     * 市场商品事件修正 Mixin 签名常量。
     */
    public static final class CommodityOnMarket {
        public static final String TARGET_CLASS = "com.fs.starfarer.campaign.econ.CommodityOnMarket";
        public static final String ADD_TRADE_MOD = "addTradeMod(Ljava/lang/String;FF)V";
        public static final String ADD_TRADE_MOD_PLUS = "addTradeModPlus(Ljava/lang/String;FF)V";
        public static final String ADD_TRADE_MOD_MINUS = "addTradeModMinus(Ljava/lang/String;FF)V";
        public static final String REAPPLY_EVENT_MOD = "reapplyEventMod()V";
        public static final String GET_AVAILABLE = "getAvailable()I";
        public static final String GET_AVAILABLE_STAT = "getAvailableStat()Lcom/fs/starfarer/api/combat/MutableStatWithTempMods;";
        public static final String REAPPLY_EVENT_MOD_TARGET = "Lcom/fs/starfarer/campaign/econ/CommodityOnMarket;reapplyEventMod()V";

        private CommodityOnMarket() {
        }
    }

    /**
     * 市场推进 Mixin 签名常量。
     */
    public static final class Market {
        public static final String TARGET_CLASS = "com.fs.starfarer.campaign.econ.Market";
        public static final String ADVANCE = "advance(F)V";

        private Market() {
        }
    }

    /**
     * 经济体市场推进降频 + 并行调度 Mixin 签名常量。
     * <p>
     * {@code Economy.advance(float)} 先执行 reach 经济 stepper（保持在循环外原样执行），
     * 再遍历 {@code getMarketsCopy()} 快照逐个调用 {@code MarketAPI.advance(amount)}，
     * Mixin redirect 循环内的这一个调用点做降频/并行分发，并在 RETURN 注入帧内屏障。
     */
    public static final class Economy {
        public static final String TARGET_CLASS = "com.fs.starfarer.campaign.econ.Economy";
        public static final String ADVANCE = "advance(F)V";
        public static final String MARKET_ADVANCE_TARGET =
                "Lcom/fs/starfarer/api/campaign/econ/MarketAPI;advance(F)V";

        private Economy() {
        }
    }

    /**
     * 战役监听器管理器同步 Mixin 签名常量。
     * <p>
     * {@code ListenerManager} 实现 {@code DoNotObfuscate}，全部 API 方法名跨版本稳定；
     * 7 个 API 方法的 {@code synchronized} 化覆写见
     * {@code github.kasuminova.ssoptimizer.mixin.campaign.ListenerManagerSyncMixin}。
     */
    public static final class ListenerManager {
        public static final String TARGET_CLASS = "com.fs.starfarer.campaign.ListenerManager";

        private ListenerManager() {
        }
    }

    /**
     * 启动器 jar 路径回填 Mixin 签名常量。
     * <p>
     * {@code launchGame} 中 {@code ScriptStore.getJarFiles()} 仅有一个调用点
     * （模组 jar 声明回填循环），重定向该点以大小写解析视图替换回填列表，
     * 注入点唯一性由 sso-app 的 {@code StarfarerLauncherJarPathAnchorTest} 核验。
     */
    public static final class StarfarerLauncher {
        public static final String TARGET_CLASS = "com.fs.starfarer.StarfarerLauncher";
        public static final String LAUNCH_GAME = "launchGame(ZZLjava/lang/String;Ljava/lang/String;)V";
        public static final String GET_JAR_FILES_TARGET =
                "Lcom/fs/starfarer/loading/scripts/ScriptStore;getJarFiles()Ljava/util/List;";

        private StarfarerLauncher() {
        }
    }

    /**
     * {@code MutableStat} 修改代际 Mixin 签名常量。
     * <p>
     * redirect 目标方法列表内联于 {@code MutableStatMutationMixin} 注解
     * （数组无编译期常量表达式），其完备性由 sso-app 的锚点测试核验。
     */
    public static final class MutableStat {
        public static final String TARGET_CLASS = "com.fs.starfarer.api.combat.MutableStat";
        public static final String NEEDS_RECOMPUTE_FIELD =
                "Lcom/fs/starfarer/api/combat/MutableStat;needsRecompute:Z";

        private MutableStat() {
        }
    }

    /**
     * 声音管理器 Mixin 签名常量。
     * <p>
     * 返回值中的 {@code sound/Audio}（linux 混淆名 {@code sound/O0OO}）已在映射表中统一命名，
     * 该描述符在 named 命名空间下跨平台一致。
     */
    public static final class SoundManager {
        public static final String TARGET_CLASS = "sound.SoundManager";
        public static final String LOAD_OBJECT_FAMILY = "loadObjectFamily(Ljava/lang/String;)Lsound/Audio;";
        public static final String LOAD_O00000_FAMILY = "loadO00000Family(Ljava/lang/String;)Lsound/Audio;";
        public static final String LOAD_O_ACCENT_FAMILY = "loadOAccentFamily(Ljava/lang/String;)Lsound/Audio;";

        private SoundManager() {
        }
    }

    /**
     * 战役舰队成员视图 Mixin 签名常量。
     */
    public static final class CampaignFleetMemberView {
        public static final String COLOR_SHIFTER_ADVANCE_TARGET = "Lcom/fs/starfarer/util/ColorShifter;advance(F)V";
        public static final String VALUE_SHIFTER_ADVANCE_TARGET = "Lcom/fs/starfarer/util/ValueShifter;advance(F)V";

        private CampaignFleetMemberView() {
        }
    }

    /**
     * 战役舰队视图 Mixin 签名常量。
     */
    public static final class CampaignFleetView {
        public static final String CONTRAIL_ADVANCE_TARGET = "Lcom/fs/starfarer/campaign/fleet/ContrailEngineV2;advance(F)V";
        public static final String CONTRAIL_RENDER_TARGET = "Lcom/fs/starfarer/campaign/fleet/ContrailEngineV2;render(F)V";

        private CampaignFleetView() {
        }
    }

    /**
     * 战役地图渲染热点 Mixin 签名常量。
     */
    public static final class CampaignLocationMapCanvas {
        public static final String TARGET_CLASS = "com.fs.starfarer.coreui.CampaignLocationMapCanvas";
        public static final String RENDER_STUFF = "renderStuff(FZ)V";
        public static final String SET_RETAIN_ALL_TARGET = "Ljava/util/Set;retainAll(Ljava/util/Collection;)Z";

        private CampaignLocationMapCanvas() {
        }
    }

    /**
     * 战役引擎战斗报告 Mixin 签名常量。
     * <p>
     * {@code CampaignEngine.reportBattleOccurred} 在迭代舰队事件监听器时，
     * 若监听器回调修改了同一列表会触发 {@code ConcurrentModificationException}。
     */
    public static final class CampaignEngine {
        public static final String TARGET_CLASS = "com.fs.starfarer.campaign.CampaignEngine";
        public static final String REPORT_BATTLE_OCCURRED =
                "reportBattleOccurred(Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;Lcom/fs/starfarer/api/campaign/BattleAPI;)V";
        public static final String GET_EVENT_LISTENERS_TARGET =
                "Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;getEventListeners()Ljava/util/List;";

        private CampaignEngine() {
        }
    }

    /**
     * 阵营管理器早期快照自愈 Mixin 签名常量。
     * <p>
     * {@code FactionManager} 构造时对 {@code SpecStore} 中的 {@code FactionSpec} 做一次性快照，
     * 若 {@code CampaignEngine} 在 faction spec 注册完成前被提前创建（如脚本编译线程上的
     * mod 脚本类静态初始化触发 {@code CampaignEngine.getInstance()}），则 player faction 永久为空。
     */
    public static final class FactionManager {
        public static final String TARGET_CLASS = "com.fs.starfarer.campaign.FactionManager";
        public static final String GET_PLAYER_FACTION = "getPlayerFaction";
        public static final String GET_FACTION = "getFaction";
        public static final String GET_ALL_FACTIONS = "getAllFactions";

        private FactionManager() {
        }
    }

    /**
     * 文本框 IME 相关桥接签名常量。
     * <p>
     * {@code releaseFocus} 的参数类型是游戏的输入事件实现类，已在两平台映射表中统一命名为
     * {@code com/fs/starfarer/util/InputEvent}（linux 混淆名 {@code com/fs/starfarer/util/super/Object}，
     * windows 混淆名 {@code com/fs/starfarer/util/A/C}），因此该描述符在 named 命名空间下跨平台一致。
     */
    public static final class TextFieldIme {
        public static final String TEXT_FIELD_API_DESC = "Lcom/fs/starfarer/api/ui/TextFieldAPI;";
        public static final String ADD_TEXT_FIELD = "addTextField";
        public static final String CREATE_TEXT_FIELD = "createTextField";
        public static final String GRAB_FOCUS = "grabFocus";
        public static final String GRAB_FOCUS_DESC = "(Z)V";
        public static final String RELEASE_FOCUS = "releaseFocus";
        public static final String RELEASE_FOCUS_DESC = "(Lcom/fs/starfarer/util/InputEvent;)V";
        public static final String TEXT_FIELD_FOCUS_HOOK_DESC = "(Lcom/fs/starfarer/api/ui/TextFieldAPI;)V";

        private TextFieldIme() {
        }
    }
}