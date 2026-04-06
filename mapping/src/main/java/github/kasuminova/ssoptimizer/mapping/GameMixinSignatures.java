package github.kasuminova.ssoptimizer.mapping;

/**
 * Mixin 运行时签名常量表。
 * <p>
 * 职责：为必须使用编译期常量的 {@code @Mixin(targets=...)}、{@code @Inject(method=...)}、
 * {@code @Shadow(aliases=...)} 等注解参数提供统一入口，避免在 {@code app} 模块中继续散落混淆类名、
 * 字段名和方法描述符字面量。<br>
 * 设计动机：{@link GameClassNames} / {@link GameMemberNames} 适合运行时查表，但注解参数要求编译期常量，
 * 因此需要在 {@code mapping} 模块集中维护一份桥接签名表。<br>
 * 兼容性策略：所有常量都必须与平台化 mapping 资源（{@code ssoptimizer-linux.tiny} /
 * {@code ssoptimizer-windows.tiny}）中的 named 命名保持语义一致；若运行时签名变化，
 * 必须同时更新映射表、该常量表和相应测试。
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
     * 声音管理器 Mixin 签名常量。
     * <p>
     * 返回值中的 {@code sound/O0OO} 仍是运行时未补命名的声音句柄类型，
     * 因而需要在 mapping 模块集中桥接，避免把描述符字面量散落到 {@code app} 模块。
     */
    public static final class SoundManager {
        public static final String TARGET_CLASS = "sound.SoundManager";
        public static final String OBFUSCATED_TARGET_CLASS_INTERNAL = "sound/Object";
        public static final String LOAD_OBJECT_FAMILY = "loadObjectFamily(Ljava/lang/String;)Lsound/O0OO;";
        public static final String LOAD_O00000_FAMILY = "loadO00000Family(Ljava/lang/String;)Lsound/O0OO;";
        public static final String LOAD_O_ACCENT_FAMILY = "loadOAccentFamily(Ljava/lang/String;)Lsound/O0OO;";

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
     * 文本框 IME 相关桥接签名常量。
     * <p>
     * 其中 {@code releaseFocus} 的参数类型当前仍是运行时未补命名的类，故保留在桥接表中集中维护，
     * 避免在 {@code app} 模块散落该描述符字面量。
     */
    public static final class TextFieldIme {
        public static final String TEXT_FIELD_API_DESC = "Lcom/fs/starfarer/api/ui/TextFieldAPI;";
        public static final String ADD_TEXT_FIELD = "addTextField";
        public static final String CREATE_TEXT_FIELD = "createTextField";
        public static final String GRAB_FOCUS = "grabFocus";
        public static final String GRAB_FOCUS_DESC = "(Z)V";
        public static final String RELEASE_FOCUS = "releaseFocus";
        public static final String RELEASE_FOCUS_DESC = "(Lcom/fs/starfarer/util/super/Object;)V";
        public static final String TEXT_FIELD_FOCUS_HOOK_DESC = "(Lcom/fs/starfarer/api/ui/TextFieldAPI;)V";

        private TextFieldIme() {
        }
    }
}