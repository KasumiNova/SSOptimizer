package github.kasuminova.ssoptimizer.common.loading;

import org.apache.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GL 显存分配分类账本（纯诊断）。
 * <p>
 * 动机：实测游戏进程独占 VRAM 远高于 LazyTextureManager 受管贴图驻留，差额来自
 * 非受管分配（游戏 FBO 附件、GraphicsLib/BoxUtil 等模组直接上传的纹理与 renderbuffer）。
 * 本类提供分类 → 原子字节数/对象数的累计账本，由各 Mixin 埋点（方法级钩子）喂数，
 * {@link #formatSummary()} 输出到贴图管理周期日志行尾。
 * <p>
 * 计量口径与已知不准之处见各埋点类 javadoc；总原则：字节数为「逻辑分配量」
 * （宽×高×每像素字节，mipmap 链按 ×4/3 近似），不含驱动对齐/压缩/页粒度开销。
 */
public final class GlMemoryLedger {
    private static final Logger LOGGER = Logger.getLogger(GlMemoryLedger.class);

    /** 账本分类；枚举顺序即 {@link #formatSummary()} 的输出顺序。 */
    public enum Category {
        /** 游戏 {@code com.fs.graphics.FrameBufferObject} 的 POT 补齐 RGBA8 颜色纹理。 */
        FBO_TEX("fboTex"),
        /** 各来源 renderbuffer（GraphicsLib 模板缓冲、BoxUtil 深度/深度模板缓冲）。 */
        RBO("rbo"),
        /** GraphicsLib 直接分配的纹理（ShaderLib 屏幕/前景/辅助缓冲、LightShader RT 纹理）。 */
        GFXLIB_TEX("gfxlibTex"),
        /** BoxUtil FBO 附件纹理（BUtil_RenderingBuffer 双层缓冲、PublicFBO）。 */
        BOX_TEX("boxTex"),
        /** 模组直接上传的贴图（BoxUtil TextureManager 统一上传头、LegacyNormalMapHelper 法线图）。 */
        UPTEX("upTex"),
        /** 模组自建的屏幕尺寸 RT 旁路（SingularityRenderer/TexTrailRenderer/BoxConfigGUI 等）。 */
        SCREEN_RT("screenRT"),
        /** 缓冲对象（BoxUtil SSBO 实例池、ParticleEngine 粒子池、游戏 SpriteBatch 批渲染 VBO）。 */
        VBO("vbo"),
        /**
         * 受管游戏贴图的真实驻留：LazyTextureManager 各上传路径（含压缩/热重传/eager）
         * 提交上传后入账、驱逐与上下文重建对称减量，外加舰船/武器图集页（只计分配峰值）。
         * 与 summary 的 {@code managedResidentMiB}（估算口径子集）不同，本分类按上传/删除
         * 的实际 GL 调用计量。
         */
        GAME_TEX("gameTex");

        private final String label;

        Category(final String label) {
            this.label = label;
        }
    }

    private static final AtomicLong[] BYTES = new AtomicLong[Category.values().length];
    private static final AtomicLong[] OBJECTS = new AtomicLong[Category.values().length];
    /** 未知内部格式告警去重（每种格式只 WARN 一次）。 */
    private static final Set<Integer> UNKNOWN_FORMAT_WARNED = ConcurrentHashMap.newKeySet();

    static {
        for (int i = 0; i < BYTES.length; i++) {
            BYTES[i] = new AtomicLong();
            OBJECTS[i] = new AtomicLong();
        }
    }

    private GlMemoryLedger() {
    }

    /**
     * 计入一次分配。
     *
     * @param category 分类
     * @param bytes    字节数（> 0）
     * @param objects  对象数（一次调用批量分配多个对象时 > 1）
     */
    public static void add(final Category category, final long bytes, final int objects) {
        BYTES[category.ordinal()].addAndGet(bytes);
        OBJECTS[category.ordinal()].addAndGet(objects);
    }

    /**
     * 对称减去一次释放；调用方保证与先前的 {@link #add} 配对（由各埋点的
     * id/实例跟踪表保证，重复减不会出现负值漂移之外的语义错误）。
     */
    public static void remove(final Category category, final long bytes, final int objects) {
        BYTES[category.ordinal()].addAndGet(-bytes);
        OBJECTS[category.ordinal()].addAndGet(-objects);
    }

    /** 当前分类字节数（测试与诊断用）。 */
    public static long bytesOf(final Category category) {
        return BYTES[category.ordinal()].get();
    }

    /** 当前分类对象数（测试与诊断用）。 */
    public static long objectsOf(final Category category) {
        return OBJECTS[category.ordinal()].get();
    }

    /**
     * 紧凑摘要：{@code glLedger fboTex=268MiB(4) rbo=133MiB(2)}。
     * 字节数 >= 1MiB 以 MiB 计，否则 KiB；零字节且零对象的分类跳过；
     * 全部为空返回空串（调用方据此决定是否拼接）。
     */
    public static String formatSummary() {
        final StringBuilder sb = new StringBuilder("glLedger");
        boolean any = false;
        for (final Category category : Category.values()) {
            final long bytes = bytesOf(category);
            final long objects = objectsOf(category);
            if (bytes == 0L && objects == 0L) {
                continue;
            }
            sb.append(' ').append(category.label).append('=').append(formatBytes(bytes))
                    .append('(').append(objects).append(')');
            any = true;
        }
        return any ? sb.toString() : "";
    }

    /**
     * GL 内部格式 → 每像素字节数。覆盖游戏与已知模组（GraphicsLib/BoxUtil）出现的
     * 常见值；未知格式按 4 字节计并对每种格式 WARN 一次（宁可高估也不漏计）。
     *
     * @param internalFormat {@code glTexImage2D}/{@code glTexStorage2D}/{@code glRenderbufferStorage}
     *                       的 internalformat 参数
     */
    public static int bytesPerPixel(final int internalFormat) {
        switch (internalFormat) {
            // RGB / RGBA 8-bit（含遗留非 sized 格式）
            case 6407:  // GL_RGB
            case 32849: // GL_RGB8
                return 3;
            case 6408:  // GL_RGBA
            case 32856: // GL_RGBA8
                return 4;
            // 16-bit 每通道整型
            case 32852: // GL_RGB16
                return 6;
            case 32859: // GL_RGBA16
                return 8;
            // 浮点
            case 33325: // GL_R16F
                return 2;
            case 33326: // GL_R32F
                return 4;
            case 34843: // GL_RGB16F
                return 6;
            case 33327: // GL_RG16F
            case 33328: // GL_RG32F
            case 34842: // GL_RGBA16F
                return 8;
            case 34837: // GL_RGB32F
                return 12;
            case 34836: // GL_RGBA32F
                return 16;
            // 8-bit 单/双通道与遗留亮度格式
            case 6402:  // GL_ALPHA
            case 6403:  // GL_RED
            case 6409:  // GL_LUMINANCE
            case 33321: // GL_R8
                return 1;
            case 6410:  // GL_LUMINANCE_ALPHA
            case 33323: // GL_RG8
                return 2;
            // 深度 / 模板
            case 36168: // GL_STENCIL_INDEX8（GraphicsLib ShaderLib.makeFramebuffer）
                return 1;
            case 33189: // GL_DEPTH_COMPONENT16（BoxUtil BUtil_RenderingBuffer）
                return 2;
            case 33190: // GL_DEPTH_COMPONENT24
                return 3;
            case 33191: // GL_DEPTH_COMPONENT32
            case 34041: // GL_DEPTH_STENCIL
            case 35056: // GL_DEPTH24_STENCIL8（BoxUtil PublicFBO）
                return 4;
            default:
                if (UNKNOWN_FORMAT_WARNED.add(internalFormat)) {
                    LOGGER.warn("[SSOptimizer] glLedger 未知 GL 内部格式 " + internalFormat
                            + "，按 4 字节/像素估算");
                }
                return 4;
        }
    }

    /** mipmap 完整链 ≈ 基础层的 4/3（几何级数 1+1/4+1/16+…）。 */
    public static long withMipmaps(final long baseBytes) {
        return baseBytes + baseBytes / 3;
    }

    /** 重置全部计数（仅供测试）。 */
    public static void reset() {
        for (int i = 0; i < BYTES.length; i++) {
            BYTES[i].set(0L);
            OBJECTS[i].set(0L);
        }
        UNKNOWN_FORMAT_WARNED.clear();
    }

    private static String formatBytes(final long bytes) {
        final long abs = Math.abs(bytes);
        if (abs >= 1024L * 1024L) {
            return (bytes / (1024L * 1024L)) + "MiB";
        }
        return (bytes / 1024L) + "KiB";
    }
}
