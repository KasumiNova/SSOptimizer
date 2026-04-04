package github.kasuminova.ssoptimizer.mapping;

/**
 * 游戏与外部运行时类的可读命名常量表。
 * <p>
 * 该表只暴露开发侧应当看到的 named 名称，禁止在业务或注入源码中继续散落
 * 混淆类名字符串。外部类必须保留上游命名空间，只做语义化去混淆，不得映射进
 * {@code github/kasuminova/ssoptimizer/**}。
 */
public final class GameClassNames {
    public static final String SPRITE                              = "com/fs/graphics/Sprite";
    public static final String ENGINE                              = "com/fs/starfarer/combat/entities/Engine";
    public static final String ENGINE_STATE                        = "com/fs/starfarer/combat/entities/EngineState";
    public static final String ENGINE_GLOW_TYPE                    = "com/fs/starfarer/combat/entities/EngineGlowType";
    public static final String ENGINE_OWNER                        = "com/fs/starfarer/combat/entities/ship/EngineOwner";
    public static final String SHIP                                = "com/fs/starfarer/combat/entities/Ship";
    public static final String ENGINE_SLOT                         = "com/fs/starfarer/loading/specs/EngineSlot";
    public static final String CONTRAIL_GROUP                      = "com/fs/starfarer/combat/entities/ContrailEngine$o";
    public static final String CONTRAIL_SEGMENT                    = "com/fs/starfarer/combat/entities/ContrailEngine$Oo";
    public static final String ENGINE_DOTTED                       = "com.fs.starfarer.combat.entities.Engine";
    public static final String ENGINE_STATE_DOTTED                 = "com.fs.starfarer.combat.entities.EngineState";
    public static final String ENGINE_GLOW_TYPE_DOTTED             = "com.fs.starfarer.combat.entities.EngineGlowType";
    public static final String ENGINE_OWNER_DOTTED                 = "com.fs.starfarer.combat.entities.ship.EngineOwner";
    public static final String SHIP_DOTTED                         = "com.fs.starfarer.combat.entities.Ship";
    public static final String ENGINE_SLOT_DOTTED                  = "com.fs.starfarer.loading.specs.EngineSlot";
    public static final String CONTRAIL_GROUP_DOTTED               = "com.fs.starfarer.combat.entities.ContrailEngine$o";
    public static final String CONTRAIL_SEGMENT_DOTTED             = "com.fs.starfarer.combat.entities.ContrailEngine$Oo";
    public static final String BITMAP_FONT_RENDERER                = "com/fs/graphics/font/BitmapFontRenderer";
    public static final String BITMAP_GLYPH                        = "com/fs/graphics/font/BitmapGlyph";
    public static final String BITMAP_FONT                         = "com/fs/graphics/font/BitmapFont";
    public static final String BITMAP_FONT_MANAGER                 = "com/fs/graphics/font/BitmapFontManager";
    public static final String TEXTURED_STRIP_RENDERER             = "com/fs/starfarer/renderers/TexturedStripRenderer";
    public static final String CONTRAIL_ENGINE                     = "com/fs/starfarer/combat/entities/ContrailEngine";
    public static final String COLLISION_GRID_QUERY                = "com/fs/starfarer/combat/CollisionGridQuery";
    public static final String COMBAT_STATE                        = "com/fs/starfarer/combat/CombatState";
    public static final String SMOOTH_PARTICLE                     = "com/fs/graphics/particle/SmoothParticle";
    public static final String DETAILED_SMOKE_PARTICLE             = "com/fs/starfarer/renderers/fx/DetailedSmokeParticle";
    public static final String GENERIC_TEXTURE_PARTICLE            = "com/fs/graphics/particle/GenericTextureParticle";
    public static final String STARFARER_LAUNCHER                  = "com/fs/starfarer/StarfarerLauncher";
    public static final String FOCUSED_COMPONENT_TRACKER           = "com/fs/starfarer/ui/FocusedComponentTracker";
    public static final String PARALLEL_IMAGE_PRELOADER            = "com/fs/graphics/ParallelImagePreloader";
    public static final String PARALLEL_IMAGE_PRELOADER_DOTTED     = "com.fs.graphics.ParallelImagePreloader";
    public static final String TEXTURE_LOADER                      = "com/fs/graphics/TextureLoader";
    public static final String TEXTURE_OBJECT                      = "com/fs/graphics/TextureObject";
    public static final String SOUND_MANAGER                       = "sound/SoundManager";
    public static final String SOUND_MANAGER_DOTTED                = "sound.SoundManager";
    public static final String RENDER_STATE_UTILS                  = "com/fs/graphics/util/RenderStateUtils";
    public static final String RENDER_STATE_UTILS_DOTTED           = "com.fs.graphics.util.RenderStateUtils";
    public static final String LOADING_UTILS                       = "com/fs/starfarer/loading/LoadingUtils";
    public static final String STARFARER_SETTINGS                  = "com/fs/starfarer/settings/StarfarerSettings";
    public static final String STARFARER_SETTINGS_DOTTED           = "com.fs.starfarer.settings.StarfarerSettings";
    public static final String CAMPAIGN_SAVE_PROGRESS_DIALOG       = "com/fs/starfarer/campaign/save/CampaignSaveProgressDialog";
    public static final String CAMPAIGN_SAVE_PROGRESS_DIALOG_DOTTED = "com.fs.starfarer.campaign.save.CampaignSaveProgressDialog";
    public static final String SAVE_PROGRESS_OUTPUT_STREAM         = "com/fs/starfarer/util/SaveProgressOutputStream";
    public static final String SAVE_PROGRESS_OUTPUT_STREAM_DOTTED  = "com.fs.starfarer.util.SaveProgressOutputStream";
    public static final String LINUX_DISPLAY                       = "org/lwjgl/opengl/LinuxDisplay";
    public static final String LINUX_EVENT                         = "org/lwjgl/opengl/LinuxEvent";
    public static final String LINUX_KEYBOARD                      = "org/lwjgl/opengl/LinuxKeyboard";
    public static final String STANDARD_TOOLTIP_V2_EXPANDABLE      = "com/fs/starfarer/ui/impl/StandardTooltipV2Expandable";
    public static final String STARFARER_SETTINGS_TEXT_FIELD_OWNER = "com/fs/starfarer/settings/StarfarerSettings$SettingsTextFieldFactory";
    public static final String TEXT_FIELD_IMPL                     = "com/fs/starfarer/ui/TextFieldImpl";
    public static final String RESOURCE_LOADER                     = "com/fs/util/ResourceLoader";

    private GameClassNames() {
    }
}