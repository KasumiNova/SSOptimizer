package github.kasuminova.ssoptimizer.mapping;

/**
 * 游戏与外部运行时成员的可读符号表。
 * <p>
 * 该表只暴露开发侧应看到的字段名、方法名和描述符；底层通过 mapping 仓库解析为
 * 真实运行时名称，避免在 ASM/Mixin 源码里继续散落混淆成员名。
 */
public final class GameMemberNames {
    private static final MappingLookup LOOKUP = new MappingLookup(TinyV2MappingRepository.loadDefault());

    public static final class ParallelImagePreloader {
        public static final String START = method(GameClassNames.PARALLEL_IMAGE_PRELOADER, "start", "()V");
        public static final String DECODE_IMAGE = method(GameClassNames.PARALLEL_IMAGE_PRELOADER, "decodeImage", "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;");
        public static final String LOAD_BYTES = method(GameClassNames.PARALLEL_IMAGE_PRELOADER, "loadBytes", "(Ljava/lang/String;)[B");
        public static final String SHUTDOWN = method(GameClassNames.PARALLEL_IMAGE_PRELOADER, "shutdown", "()V");
        public static final String AWAIT_BYTES = method(GameClassNames.PARALLEL_IMAGE_PRELOADER, "awaitBytes", "(Ljava/lang/String;)[B");
        public static final String AWAIT_IMAGE = method(GameClassNames.PARALLEL_IMAGE_PRELOADER, "awaitImage", "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;");
        public static final String ENQUEUE_IMAGE = method(GameClassNames.PARALLEL_IMAGE_PRELOADER, "enqueueImage", "(Ljava/lang/String;)V");
        public static final String ENQUEUE_BYTES = method(GameClassNames.PARALLEL_IMAGE_PRELOADER, "enqueueBytes", "(Ljava/lang/String;)V");

        public static final String IMAGE_QUEUE = field(GameClassNames.PARALLEL_IMAGE_PRELOADER, "imageQueue");
        public static final String IMAGE_RESULTS = field(GameClassNames.PARALLEL_IMAGE_PRELOADER, "imageResults");
        public static final String IMAGE_SENTINEL = field(GameClassNames.PARALLEL_IMAGE_PRELOADER, "imageSentinel");
        public static final String BYTE_QUEUE = field(GameClassNames.PARALLEL_IMAGE_PRELOADER, "byteQueue");
        public static final String BYTE_RESULTS = field(GameClassNames.PARALLEL_IMAGE_PRELOADER, "byteResults");
        public static final String BYTE_SENTINEL = field(GameClassNames.PARALLEL_IMAGE_PRELOADER, "byteSentinel");

        private ParallelImagePreloader() {
        }
    }

    public static final class CollisionGridQuery {
        public static final String CELLS = field(GameClassNames.COLLISION_GRID_QUERY, "cells");
        public static final String GRID_WIDTH = field(GameClassNames.COLLISION_GRID_QUERY, "gridWidth");
        public static final String GRID_HEIGHT = field(GameClassNames.COLLISION_GRID_QUERY, "gridHeight");
        public static final String BASE_X = field(GameClassNames.COLLISION_GRID_QUERY, "baseX");
        public static final String BASE_Y = field(GameClassNames.COLLISION_GRID_QUERY, "baseY");
        public static final String CELL_SIZE = field(GameClassNames.COLLISION_GRID_QUERY, "cellSize");

        private CollisionGridQuery() {
        }
    }

    public static final class ContrailEngine {
        public static final String RENDER = method(GameClassNames.CONTRAIL_ENGINE, "render", "(F)V");
        public static final String GROUPS = field(GameClassNames.CONTRAIL_ENGINE, "groups");

        private ContrailEngine() {
        }
    }

    public static final class TextureLoader {
        public static final String CONVERT_PIXELS = method(
                GameClassNames.TEXTURE_LOADER,
                "convertPixels",
                "(Ljava/awt/image/BufferedImage;Lcom/fs/graphics/TextureObject;)Ljava/nio/ByteBuffer;");
        public static final String LOAD_TEXTURE = method(
                GameClassNames.TEXTURE_LOADER,
                "loadTexture",
                "(Ljava/lang/String;)Lcom/fs/graphics/TextureObject;");
        public static final String TEXTURE_DIMENSION = method(GameClassNames.TEXTURE_LOADER, "textureDimension", "(I)I");

        public static final String TEXTURE_CACHE = field(GameClassNames.TEXTURE_LOADER, "textureCache");
        public static final String SPECIAL_MIPMAP_SET = field(GameClassNames.TEXTURE_LOADER, "specialMipmapSet");
        public static final String UPPER_HALF_COLOR = field(GameClassNames.TEXTURE_LOADER, "upperHalfColor");
        public static final String AVERAGE_COLOR = field(GameClassNames.TEXTURE_LOADER, "averageColor");
        public static final String LOWER_HALF_COLOR = field(GameClassNames.TEXTURE_LOADER, "lowerHalfColor");

        private TextureLoader() {
        }
    }

    public static final class TextureManager {
        public static final String IS_LAZY_LOADING_ENABLED = method(
                GameClassNames.TEXTURE_MANAGER,
                "isLazyLoadingEnabled",
                "()Z");

        private TextureManager() {
        }
    }

    public static final class BitmapFontRenderer {
        public static final String RENDER = method(GameClassNames.BITMAP_FONT_RENDERER, "render", "()V");
        public static final String DRAW_GLYPH = method(
                GameClassNames.BITMAP_FONT_RENDERER,
                "drawGlyph",
                "(FFLcom/fs/graphics/font/BitmapGlyph;FZ)V");

        public static final String FONT = field(GameClassNames.BITMAP_FONT_RENDERER, "font");
        public static final String REQUESTED_FONT_SIZE = field(GameClassNames.BITMAP_FONT_RENDERER, "requestedFontSize");
        public static final String SHADOW_COPIES = field(GameClassNames.BITMAP_FONT_RENDERER, "shadowCopies");
        public static final String SHADOW_SCALE = field(GameClassNames.BITMAP_FONT_RENDERER, "shadowScale");

        private BitmapFontRenderer() {
        }
    }

    public static final class BitmapGlyph {
        public static final String GET_GLYPH_ID = method(GameClassNames.BITMAP_GLYPH, "getGlyphId", "()I");
        public static final String GET_X_OFFSET = method(GameClassNames.BITMAP_GLYPH, "getXOffset", "()I");
        public static final String GET_X_ADVANCE = method(GameClassNames.BITMAP_GLYPH, "getXAdvance", "()I");
        public static final String GET_WIDTH = method(GameClassNames.BITMAP_GLYPH, "getWidth", "()I");
        public static final String GET_HEIGHT = method(GameClassNames.BITMAP_GLYPH, "getHeight", "()I");
        public static final String GET_BEARING_Y = method(GameClassNames.BITMAP_GLYPH, "getBearingY", "()I");
        public static final String GET_TEX_X = method(GameClassNames.BITMAP_GLYPH, "getTexX", "()F");
        public static final String GET_TEX_Y = method(GameClassNames.BITMAP_GLYPH, "getTexY", "()F");
        public static final String GET_TEX_WIDTH = method(GameClassNames.BITMAP_GLYPH, "getTexWidth", "()F");
        public static final String GET_TEX_HEIGHT = method(GameClassNames.BITMAP_GLYPH, "getTexHeight", "()F");

        private BitmapGlyph() {
        }
    }

    public static final class BitmapFont {
        public static final String GET_FONT_PATH = method(GameClassNames.BITMAP_FONT, "getFontPath", "()Ljava/lang/String;");
        public static final String GET_NOMINAL_FONT_SIZE = method(GameClassNames.BITMAP_FONT, "getNominalFontSize", "()I");
        public static final String GET_LINE_HEIGHT = method(GameClassNames.BITMAP_FONT, "getLineHeight", "()I");

        private BitmapFont() {
        }
    }

    public static final class BitmapFontManager {
        public static final String GET_FONT = method(
                GameClassNames.BITMAP_FONT_MANAGER,
                "getFont",
                "(Ljava/lang/String;)Lcom/fs/graphics/font/BitmapFont;");

        private BitmapFontManager() {
        }
    }

    public static final class TextureObject {
        public static final String IS_DEFERRED_LOADING_ENABLED = method(GameClassNames.TEXTURE_OBJECT, "isDeferredLoadingEnabled", "()Z");
        public static final String SET_DEFERRED_LOADING_ENABLED = method(GameClassNames.TEXTURE_OBJECT, "setDeferredLoadingEnabled", "(Z)V");
        public static final String BIND = method(GameClassNames.TEXTURE_OBJECT, "bind", "()V");
        public static final String GET_TEXTURE_ID = method(GameClassNames.TEXTURE_OBJECT, "getTextureId", "()I");
        public static final String BIND_TARGET = field(GameClassNames.TEXTURE_OBJECT, "bindTarget");
        public static final String TEXTURE_ID = field(GameClassNames.TEXTURE_OBJECT, "textureId");
        public static final String SET_IMAGE_WIDTH = method(GameClassNames.TEXTURE_OBJECT, "setImageWidth", "(I)V");
        public static final String SET_IMAGE_HEIGHT = method(GameClassNames.TEXTURE_OBJECT, "setImageHeight", "(I)V");
        public static final String SET_TEXTURE_HEIGHT = method(GameClassNames.TEXTURE_OBJECT, "setTextureHeight", "(I)V");
        public static final String SET_TEXTURE_WIDTH = method(GameClassNames.TEXTURE_OBJECT, "setTextureWidth", "(I)V");
        public static final String SET_AVERAGE_COLOR = method(GameClassNames.TEXTURE_OBJECT, "setAverageColor", "(Ljava/awt/Color;)V");
        public static final String SET_UPPER_HALF_COLOR = method(GameClassNames.TEXTURE_OBJECT, "setUpperHalfColor", "(Ljava/awt/Color;)V");
        public static final String SET_LOWER_HALF_COLOR = method(GameClassNames.TEXTURE_OBJECT, "setLowerHalfColor", "(Ljava/awt/Color;)V");

        private TextureObject() {
        }
    }

    public static final class TexturedStripRenderer {
        public static final String RENDER_TEXTURED_STRIP = method(
                GameClassNames.TEXTURED_STRIP_RENDERER,
                "renderTexturedStrip",
                "(Lcom/fs/graphics/TextureObject;FFFFFFLjava/awt/Color;FFFZ)V");

        private TexturedStripRenderer() {
        }
    }

    public static final class LoadingUtils {
        public static final String READ_TEXT = method(GameClassNames.LOADING_UTILS, "readText", "(Ljava/io/InputStream;)Ljava/lang/String;");

        private LoadingUtils() {
        }
    }

    public static final class FocusedComponentTracker {
        public static final String GET_CURRENT_FOCUSED_COMPONENT = method(
                GameClassNames.FOCUSED_COMPONENT_TRACKER,
                "getCurrentFocusedComponent",
                "()Lcom/fs/starfarer/ui/S;");

        private FocusedComponentTracker() {
        }
    }

    public static final class RenderStateUtils {
        public static final String ENABLE_TEXTURE_CLAMP = method(GameClassNames.RENDER_STATE_UTILS, "enableTextureClamp", "()V");
        public static final String RESTORE_TEXTURE_CLAMP = method(GameClassNames.RENDER_STATE_UTILS, "restoreTextureClamp", "()V");
        public static final String BEGIN_SCREEN_OVERLAY = method(GameClassNames.RENDER_STATE_UTILS, "beginScreenOverlay", "(FFFFF)V");
        public static final String END_SCREEN_OVERLAY = method(GameClassNames.RENDER_STATE_UTILS, "endScreenOverlay", "()V");
        public static final String ADJUST_BRIGHTNESS = method(
                GameClassNames.RENDER_STATE_UTILS,
                "adjustBrightness",
                "(Ljava/awt/Color;F)Ljava/awt/Color;");
        public static final String BLEND_COLORS = method(
            GameClassNames.RENDER_STATE_UTILS,
            "blendColors",
            "(Ljava/awt/Color;Ljava/awt/Color;F)Ljava/awt/Color;");

        private RenderStateUtils() {
        }
    }

    public static final class EngineGlowType {
        public static final String PRIMARY = field(GameClassNames.ENGINE_GLOW_TYPE, "PRIMARY");

        private EngineGlowType() {
        }
    }

    public static final class StarfarerSettings {
        public static final String GET_BOOLEAN = method(
                GameClassNames.STARFARER_SETTINGS,
                "getBoolean",
                "(Ljava/lang/String;)Z");

        private StarfarerSettings() {
        }
    }

    public static final class CampaignSaveProgressDialog {
        public static final String REPORT_PROGRESS_WITH_TEXT = method(
                GameClassNames.CAMPAIGN_SAVE_PROGRESS_DIALOG,
                "reportProgress",
                "(Ljava/lang/String;F)V");
        public static final String REPORT_PROGRESS = method(
                GameClassNames.CAMPAIGN_SAVE_PROGRESS_DIALOG,
                "reportProgress",
                "(F)V");

        private CampaignSaveProgressDialog() {
        }
    }

    public static final class SaveProgressOutputStream {
        public static final String WRITTEN_BYTES = field(GameClassNames.SAVE_PROGRESS_OUTPUT_STREAM, "writtenBytes");
        public static final String GET_WRITTEN_BYTES = method(
                GameClassNames.SAVE_PROGRESS_OUTPUT_STREAM,
                "getWrittenBytes",
                "()J");

        private SaveProgressOutputStream() {
        }
    }

    public static final class CommodityOnMarket {
        public static final String ADD_TRADE_MOD = method(
                GameClassNames.COMMODITY_ON_MARKET,
                "addTradeMod",
                "(Ljava/lang/String;FF)V");
        public static final String ADD_TRADE_MOD_PLUS = method(
                GameClassNames.COMMODITY_ON_MARKET,
                "addTradeModPlus",
                "(Ljava/lang/String;FF)V");
        public static final String ADD_TRADE_MOD_MINUS = method(
                GameClassNames.COMMODITY_ON_MARKET,
                "addTradeModMinus",
                "(Ljava/lang/String;FF)V");
        public static final String REAPPLY_EVENT_MOD = method(
                GameClassNames.COMMODITY_ON_MARKET,
                "reapplyEventMod",
                "()V");
        public static final String GET_AVAILABLE = method(
                GameClassNames.COMMODITY_ON_MARKET,
                "getAvailable",
                "()I");
        public static final String GET_AVAILABLE_STAT = method(
                GameClassNames.COMMODITY_ON_MARKET,
                "getAvailableStat",
                "()Lcom/fs/starfarer/api/combat/MutableStatWithTempMods;");

        private CommodityOnMarket() {
        }
    }

    public static final class Market {
        public static final String ADVANCE = method(GameClassNames.MARKET, "advance", "(F)V");

        private Market() {
        }
    }

    public static final class SoundManager {
        public static final String LOAD_OBJECT_FAMILY_FROM_STREAM = method(
                GameClassNames.SOUND_MANAGER,
                "loadObjectFamilyFromStream",
                "(Ljava/lang/String;Ljava/io/InputStream;)Lsound/O0OO;");
        public static final String LOAD_O00000_FAMILY_FROM_STREAM = method(
                GameClassNames.SOUND_MANAGER,
                "loadO00000FamilyFromStream",
                "(Ljava/lang/String;Ljava/io/InputStream;)Lsound/O0OO;");
        public static final String LOAD_O_ACCENT_FAMILY_FROM_STREAM = method(
                GameClassNames.SOUND_MANAGER,
                "loadOAccentFamilyFromStream",
                "(Ljava/lang/String;Ljava/io/InputStream;)Lsound/O0OO;");

        private SoundManager() {
        }
    }

    private GameMemberNames() {
    }

    private static String field(String ownerNamedName, String fieldName) {
        return LOOKUP.requireFieldByNamedName(ownerNamedName, fieldName).namedName();
    }

    private static String method(String ownerNamedName, String methodName, String descriptor) {
        return LOOKUP.requireMethodByNamedName(ownerNamedName, methodName, descriptor).namedName();
    }
}