package github.kasuminova.ssoptimizer.mapping;

/** Mixin 注解使用的编译期签名常量，成员名与描述符须与 named 映射一致。 */
public final class GameMixinSignatures {
    private GameMixinSignatures() {
    }

    public static final class CampaignSaveProgressDialog {
        public static final String TARGET_CLASS = "com.fs.starfarer.campaign.save.CampaignSaveProgressDialog";
        public static final String STRING_CONSTRUCTOR = "<init>(Ljava/lang/String;)V";
        public static final String REPORT_PROGRESS_WITH_TEXT = "reportProgress(Ljava/lang/String;F)V";
        public static final String REPORT_PROGRESS = "reportProgress(F)V";

        private CampaignSaveProgressDialog() {
        }
    }

    public static final class SaveProgressOutputStream {
        public static final String TARGET_CLASS = "com.fs.starfarer.util.SaveProgressOutputStream";
        public static final String PROGRESS_CONSTRUCTOR = "<init>(Ljava/io/OutputStream;JFFLcom/fs/starfarer/campaign/save/CampaignSaveProgressDialog;)V";
        public static final String WRITE_BYTES = "write([BII)V";
        public static final String CLOSE = "close()V";
        public static final String WRITTEN_BYTES_FIELD = "writtenBytes";

        private SaveProgressOutputStream() {
        }
    }

    public static final class SaveProgressInputStream {
        public static final String TARGET_CLASS = "com.fs.starfarer.util.SaveProgressInputStream";
        public static final String UPDATE_PROGRESS = "updateProgress(Z)V";
        public static final String MARK_COMPLETE = "markComplete()V";

        private SaveProgressInputStream() {
        }
    }

    public static final class CampaignGameManager {
        public static final String TARGET_CLASS = "com.fs.starfarer.campaign.save.CampaignGameManager";
        public static final String LOAD_GAME = "loadGame(Ljava/lang/String;Lcom/fs/starfarer/campaign/CampaignListener;Lcom/fs/starfarer/campaign/CampaignEngine$CampaignUI;ZZ)Ljava/lang/String;";
        public static final String MOD_PLUGIN_ON_GAME_LOAD = "Lcom/fs/starfarer/api/ModPlugin;onGameLoad(Z)V";
        public static final String ENGINE_SET_INSTANCE =
                "Lcom/fs/starfarer/campaign/CampaignEngine;setInstance(Lcom/fs/starfarer/campaign/CampaignEngine;)V";

        private CampaignGameManager() {
        }
    }

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

    public static final class Market {
        public static final String TARGET_CLASS = "com.fs.starfarer.campaign.econ.Market";
        public static final String ADVANCE = "advance(F)V";

        private Market() {
        }
    }

    public static final class Economy {
        public static final String TARGET_CLASS = "com.fs.starfarer.campaign.econ.Economy";
        public static final String ADVANCE = "advance(F)V";
        public static final String MARKET_ADVANCE_TARGET =
                "Lcom/fs/starfarer/api/campaign/econ/MarketAPI;advance(F)V";

        private Economy() {
        }
    }

    public static final class ListenerManager {
        public static final String TARGET_CLASS = "com.fs.starfarer.campaign.ListenerManager";

        private ListenerManager() {
        }
    }

    public static final class StarfarerLauncher {
        public static final String TARGET_CLASS = "com.fs.starfarer.StarfarerLauncher";
        public static final String LAUNCH_GAME = "launchGame(ZZLjava/lang/String;Ljava/lang/String;)V";
        public static final String GET_JAR_FILES_TARGET =
                "Lcom/fs/starfarer/loading/scripts/ScriptStore;getJarFiles()Ljava/util/List;";

        private StarfarerLauncher() {
        }
    }

    public static final class MutableStat {
        public static final String TARGET_CLASS = "com.fs.starfarer.api.combat.MutableStat";
        public static final String NEEDS_RECOMPUTE_FIELD =
                "Lcom/fs/starfarer/api/combat/MutableStat;needsRecompute:Z";

        private MutableStat() {
        }
    }

    public static final class SoundManager {
        public static final String TARGET_CLASS = "sound.SoundManager";
        public static final String LOAD_OBJECT_FAMILY = "loadObjectFamily(Ljava/lang/String;)Lsound/Audio;";
        public static final String LOAD_O00000_FAMILY = "loadO00000Family(Ljava/lang/String;)Lsound/Audio;";
        public static final String LOAD_O_ACCENT_FAMILY = "loadOAccentFamily(Ljava/lang/String;)Lsound/Audio;";

        private SoundManager() {
        }
    }

    public static final class CampaignFleetMemberView {
        public static final String COLOR_SHIFTER_ADVANCE_TARGET = "Lcom/fs/starfarer/util/ColorShifter;advance(F)V";
        public static final String VALUE_SHIFTER_ADVANCE_TARGET = "Lcom/fs/starfarer/util/ValueShifter;advance(F)V";

        private CampaignFleetMemberView() {
        }
    }

    public static final class CampaignFleetView {
        public static final String CONTRAIL_ADVANCE_TARGET = "Lcom/fs/starfarer/campaign/fleet/ContrailEngineV2;advance(F)V";
        public static final String CONTRAIL_RENDER_TARGET = "Lcom/fs/starfarer/campaign/fleet/ContrailEngineV2;render(F)V";

        private CampaignFleetView() {
        }
    }

    public static final class CampaignLocationMapCanvas {
        public static final String TARGET_CLASS = "com.fs.starfarer.coreui.CampaignLocationMapCanvas";
        public static final String RENDER_STUFF = "renderStuff(FZ)V";
        public static final String SET_RETAIN_ALL_TARGET = "Ljava/util/Set;retainAll(Ljava/util/Collection;)Z";

        private CampaignLocationMapCanvas() {
        }
    }

    public static final class CampaignEngine {
        public static final String TARGET_CLASS = "com.fs.starfarer.campaign.CampaignEngine";
        public static final String REPORT_BATTLE_OCCURRED =
                "reportBattleOccurred(Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;Lcom/fs/starfarer/api/campaign/BattleAPI;)V";
        public static final String GET_EVENT_LISTENERS_TARGET =
                "Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;getEventListeners()Ljava/util/List;";
        public static final String GET_ENTITY_BY_ID =
                "getEntityById(Ljava/lang/String;)Lcom/fs/starfarer/api/campaign/SectorEntityToken;";
        public static final String REBUILD_ID_TO_ENTITY_MAP = "rebuildIDToEntityMap()V";
        public static final String OBJECT_ADDED = "objectAdded(Ljava/lang/Object;)V";
        public static final String OBJECT_REMOVED = "objectRemoved(Ljava/lang/Object;)V";
        public static final String READD_CHANGE_LISTENERS = "readdChangeListeners()V";

        private CampaignEngine() {
        }
    }

    public static final class FactionManager {
        public static final String TARGET_CLASS = "com.fs.starfarer.campaign.FactionManager";
        public static final String GET_PLAYER_FACTION = "getPlayerFaction";
        public static final String GET_FACTION = "getFaction";
        public static final String GET_ALL_FACTIONS = "getAllFactions";

        private FactionManager() {
        }
    }

    public static final class BaseTiledTerrain {
        public static final String TARGET_CLASS = "com.fs.starfarer.api.impl.campaign.terrain.BaseTiledTerrain";
        public static final String TILE_RANDOM_NEW = "java/util/Random";

        private BaseTiledTerrain() {
        }
    }

    public static final class HyperspaceTerrainPlugin {
        public static final String TARGET_CLASS = "com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin";
        public static final String TILE_RANDOM_NEW = "java/util/Random";

        private HyperspaceTerrainPlugin() {
        }
    }

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

    public static final class BaseLocation {
        public static final String TARGET_CLASS = "com.fs.starfarer.campaign.BaseLocation";
        public static final String ADVANCE = "advance(FLcom/fs/starfarer/util/InputEventList;)V";

        private BaseLocation() {
        }
    }

    public static final class TacticalModule {
        public static final String TARGET_CLASS = "com.fs.starfarer.campaign.ai.TacticalModule";
        public static final String ADVANCE = "advance(F)V";
        public static final String GET_LIST_TARGET =
                "Lcom/fs/util/container/repo/ObjectRepository;getList(Ljava/lang/Class;)Ljava/util/List;";

        private TacticalModule() {
        }
    }

    public static final class PositionImpl {
        public static final String TARGET_CLASS = "com.fs.starfarer.ui.PositionImpl";

        private PositionImpl() {
        }
    }
}