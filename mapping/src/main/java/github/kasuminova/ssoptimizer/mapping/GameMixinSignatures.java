package github.kasuminova.ssoptimizer.mapping;

/**
 * Mixin 运行时签名常量表。
 * <p>
 * 职责：为必须使用编译期常量的 {@code @Mixin(targets=...)}、{@code @Inject(method=...)}、
 * {@code @Shadow(aliases=...)} 等注解参数提供统一入口，避免在 {@code app} 模块中继续散落混淆类名、
 * 字段名和方法描述符字面量。<br>
 * 设计动机：{@link GameClassNames} / {@link GameMemberNames} 适合运行时查表，但注解参数要求编译期常量，
 * 因此需要在 {@code mapping} 模块集中维护一份桥接签名表。<br>
 * 兼容性策略：所有常量都必须与 {@code ssoptimizer.tiny} 中的 named 命名保持语义一致；若运行时签名变化，
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
}