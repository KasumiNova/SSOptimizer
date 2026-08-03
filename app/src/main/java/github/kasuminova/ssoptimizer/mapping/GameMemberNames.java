package github.kasuminova.ssoptimizer.mapping;

/**
 * 游戏与外部运行时成员的可读符号表（纯 named 常量 holder）。
 * <p>
 * 游戏 jar 在磁盘上已是 named 版本（NanoForge 全量 deobf 运行时 + SourceSector
 * 三命名空间全量表），成员名直接以 named 名书写，无运行期查表；本类仅集中登记
 * ASM/Mixin 源码使用的成员名字符串，避免散落字面量。
 */
public final class GameMemberNames {

    public static final class ParallelImagePreloader {
        public static final String START = "start";
        public static final String DECODE_IMAGE = "decodeImage";
        public static final String LOAD_BYTES = "loadBytes";
        public static final String SHUTDOWN = "shutdown";
        public static final String AWAIT_BYTES = "awaitBytes";
        public static final String AWAIT_IMAGE = "awaitImage";
        public static final String ENQUEUE_IMAGE = "enqueueImage";
        public static final String ENQUEUE_BYTES = "enqueueBytes";

        public static final String IMAGE_QUEUE = "imageQueue";
        public static final String IMAGE_RESULTS = "imageResults";
        public static final String IMAGE_SENTINEL = "imageSentinel";
        public static final String BYTE_QUEUE = "byteQueue";
        public static final String BYTE_RESULTS = "byteResults";
        public static final String BYTE_SENTINEL = "byteSentinel";

        private ParallelImagePreloader() {
        }
    }

    public static final class CollisionGridQuery {
        public static final String CELLS = "cells";
        public static final String GRID_WIDTH = "gridWidth";
        public static final String GRID_HEIGHT = "gridHeight";
        public static final String BASE_X = "baseX";
        public static final String BASE_Y = "baseY";
        public static final String CELL_SIZE = "cellSize";

        private CollisionGridQuery() {
        }
    }

    public static final class ContrailEngine {
        public static final String RENDER = "render";
        public static final String GROUPS = "groups";

        private ContrailEngine() {
        }
    }

    public static final class TextureLoader {
        public static final String CONVERT_PIXELS = "convertPixels";
        public static final String LOAD_TEXTURE = "loadTexture";
        public static final String READ_IMAGE = "readImage";
        public static final String TEXTURE_DIMENSION = "textureDimension";

        public static final String TEXTURE_CACHE = "textureCache";
        public static final String SPECIAL_MIPMAP_SET = "specialMipmapSet";
        public static final String UPPER_HALF_COLOR = "upperHalfColor";
        public static final String AVERAGE_COLOR = "averageColor";
        public static final String LOWER_HALF_COLOR = "lowerHalfColor";

        private TextureLoader() {
        }
    }

    public static final class TextureManager {
        public static final String IS_LAZY_LOADING_ENABLED = "isLazyLoadingEnabled";

        private TextureManager() {
        }
    }

    public static final class BitmapFontRenderer {
        public static final String RENDER = "render";
        public static final String DRAW_GLYPH = "drawGlyph";

        public static final String FONT = "font";
        public static final String REQUESTED_FONT_SIZE = "requestedFontSize";
        public static final String SHADOW_COPIES = "shadowCopies";
        public static final String SHADOW_SCALE = "shadowScale";

        private BitmapFontRenderer() {
        }
    }

    public static final class BitmapGlyph {
        public static final String GET_GLYPH_ID = "getGlyphId";
        public static final String GET_X_OFFSET = "getXOffset";
        public static final String GET_X_ADVANCE = "getXAdvance";
        public static final String GET_WIDTH = "getWidth";
        public static final String GET_HEIGHT = "getHeight";
        public static final String GET_BEARING_Y = "getBearingY";
        public static final String GET_TEX_X = "getTexX";
        public static final String GET_TEX_Y = "getTexY";
        public static final String GET_TEX_WIDTH = "getTexWidth";
        public static final String GET_TEX_HEIGHT = "getTexHeight";

        private BitmapGlyph() {
        }
    }

    public static final class BitmapFont {
        public static final String GET_FONT_PATH = "getFontPath";
        public static final String GET_NOMINAL_FONT_SIZE = "getNominalFontSize";
        public static final String GET_LINE_HEIGHT = "getLineHeight";

        private BitmapFont() {
        }
    }

    public static final class BitmapFontManager {
        public static final String GET_FONT = "getFont";

        private BitmapFontManager() {
        }
    }

    public static final class TextureObject {
        public static final String IS_DEFERRED_LOADING_ENABLED = "isDeferredLoadingEnabled";
        public static final String SET_DEFERRED_LOADING_ENABLED = "setDeferredLoadingEnabled";
        public static final String BIND = "bind";
        public static final String GET_TEXTURE_ID = "getTextureId";
        public static final String BIND_TARGET = "bindTarget";
        public static final String TEXTURE_ID = "textureId";
        public static final String SET_IMAGE_WIDTH = "setImageWidth";
        public static final String SET_IMAGE_HEIGHT = "setImageHeight";
        public static final String SET_TEXTURE_HEIGHT = "setTextureHeight";
        public static final String SET_TEXTURE_WIDTH = "setTextureWidth";
        public static final String SET_AVERAGE_COLOR = "setAverageColor";
        public static final String SET_UPPER_HALF_COLOR = "setUpperHalfColor";
        public static final String SET_LOWER_HALF_COLOR = "setLowerHalfColor";

        private TextureObject() {
        }
    }

    public static final class TexturedStripRenderer {
        public static final String RENDER_TEXTURED_STRIP = "renderTexturedStrip";

        private TexturedStripRenderer() {
        }
    }

    public static final class LoadingUtils {
        public static final String READ_TEXT = "readText";

        private LoadingUtils() {
        }
    }

    public static final class ResourceLoader {
        public static final String OPEN_STREAM = "openStream";

        private ResourceLoader() {
        }
    }

    public static final class FocusedComponentTracker {
        public static final String GET_CURRENT_FOCUSED_COMPONENT = "getCurrentFocusedComponent";

        private FocusedComponentTracker() {
        }
    }

    public static final class RenderStateUtils {
        public static final String ENABLE_TEXTURE_CLAMP = "enableTextureClamp";
        public static final String RESTORE_TEXTURE_CLAMP = "restoreTextureClamp";
        public static final String BEGIN_SCREEN_OVERLAY = "beginScreenOverlay";
        public static final String END_SCREEN_OVERLAY = "endScreenOverlay";
        public static final String ADJUST_BRIGHTNESS = "adjustBrightness";
        public static final String BLEND_COLORS = "blendColors";

        private RenderStateUtils() {
        }
    }

    public static final class EngineGlowType {
        public static final String PRIMARY = "PRIMARY";

        private EngineGlowType() {
        }
    }

    public static final class StarfarerSettings {
        public static final String GET_BOOLEAN = "getBoolean";

        private StarfarerSettings() {
        }
    }

    public static final class CampaignSaveProgressDialog {
        public static final String REPORT_PROGRESS_WITH_TEXT = "reportProgress";
        public static final String REPORT_PROGRESS = "reportProgress";

        private CampaignSaveProgressDialog() {
        }
    }

    public static final class SaveProgressOutputStream {
        public static final String WRITTEN_BYTES = "writtenBytes";
        public static final String GET_WRITTEN_BYTES = "getWrittenBytes";

        private SaveProgressOutputStream() {
        }
    }

    public static final class CommodityOnMarket {
        public static final String ADD_TRADE_MOD = "addTradeMod";
        public static final String ADD_TRADE_MOD_PLUS = "addTradeModPlus";
        public static final String ADD_TRADE_MOD_MINUS = "addTradeModMinus";
        public static final String REAPPLY_EVENT_MOD = "reapplyEventMod";
        public static final String GET_AVAILABLE = "getAvailable";
        public static final String GET_AVAILABLE_STAT = "getAvailableStat";

        private CommodityOnMarket() {
        }
    }

    public static final class Market {
        public static final String ADVANCE = "advance";

        private Market() {
        }
    }

    public static final class SoundManager {
        public static final String LOAD_OBJECT_FAMILY_FROM_STREAM = "loadObjectFamilyFromStream";
        public static final String LOAD_O00000_FAMILY_FROM_STREAM = "loadO00000FamilyFromStream";
        public static final String LOAD_O_ACCENT_FAMILY_FROM_STREAM = "loadOAccentFamilyFromStream";

        private SoundManager() {
        }
    }

    private GameMemberNames() {
    }

}