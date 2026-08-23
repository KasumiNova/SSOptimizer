package github.kasuminova.ssoptimizer.common.loading;

import org.apache.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GL 显存账本的埋点入口（被各 Mixin 回调直调，集中承担字节计算与对象生命周期跟踪）。
 * <p>
 * 跟踪模型：
 * <ul>
 * <li>游戏 FBO：fboId → 字节，create/delete 对称（颜色纹理在 {@code bindFramebuffer} 后
 * 转入 Sprite 继续存活、{@code deleteFramebuffer} 并不真实删除纹理，本账本按 FBO 包装
 * 生命周期计，存在低估窗口，仅 3 个实例影响可忽略）；</li>
 * <li>GraphicsLib ShaderLib 屏幕缓冲：纹理 id → 字节，重复 init 按 id 替换；
 * renderbuffer 随 {@code makeFramebuffer} 返回值登记，GraphicsLib 无对应删除路径，
 * <b>只计分配峰值</b>；</li>
 * <li>GraphicsLib LightShader：实例 → 字节，{@code destroy()} 对称移除；</li>
 * <li>BoxUtil BUtil_RenderingBuffer：实例 → 字节；{@code delete(int)} 按层部分删除且
 * bloom ping-pong 0 号位与 {@code texID[1][0]} 存在别名共享，逐层对称必然双减，
 * <b>只计分配峰值</b>（该对象正常路径全局长存，峰值即实况）；</li>
 * <li>BoxUtil PublicFBO：实例 → 字节，{@code delete()} 对称移除。</li>
 * <li>upTex/screenRT/gameTex 纹理（前两者为 ASM 重定向 glTexImage/glTexStorage/
 * glCopyTexImage 调用点至本类转发钩子，gameTex 由 LazyTextureManager/ShipWeaponAtlas
 * 上传路径直调）：纹理 id 经「分配时查询当前绑定」或调用方已知 id 获得，按 id 替换；有
 * glDeleteTextures 路径的类（TextureManager/Moci/No101/ASTD、LTM 驱逐/上下文重建）
 * 做对称减量，无删除路径的类（LegacyNormalMapHelper/BoxConfigGUI/ShipWeaponAtlas
 * 图集页）<b>只计分配峰值</b>；</li>
 * <li>vbo 缓冲对象（ASM 重定向 glBufferData/glBufferStorage + 游戏 SpriteBatch Mixin）：
 * buffer id 同上按绑定查询，同 id 重分配按替换计（GL 语义即旧存储释放）；
 * 有 glDeleteBuffers 路径的做对称，无删除路径的（ParticleEngine）<b>只计分配峰值</b>。</li>
 * </ul>
 * 尺寸来源：GraphicsLib 经 {@code ShaderLib.getInternalWidth/Height} 返回值缓存；
 * BoxUtil 经 {@code ShaderCore.getScreenScaleWidth/Height} 返回值缓存——均与方法体内
 * 实际使用的取值路径一致。
 */
public final class GlLedgerHooks {
    private static final Logger LOGGER = Logger.getLogger(GlLedgerHooks.class);

    private static final Object LOCK = new Object();

    /** 游戏 FBO：fboId → 颜色纹理字节。 */
    private static final Map<Integer, Long> VANILLA_FBO = new HashMap<>();
    /** GraphicsLib ShaderLib 屏幕缓冲：纹理 id → 字节（重 init 时按 id 替换）。 */
    private static final Map<Integer, Long> GFXLIB_TEXTURES = new HashMap<>();
    /** GraphicsLib makeFramebuffer 登记的 renderbuffer：fboId → 字节（无删除路径，峰值）。 */
    private static final Map<Integer, Long> GFXLIB_RENDERBUFFERS = new HashMap<>();
    /** GraphicsLib LightShader 实例 → [字节, 对象数]。 */
    private static final Map<Object, long[]> LIGHT_SHADERS = new WeakHashMap<>();
    /** BoxUtil BUtil_RenderingBuffer 实例 → 字节（峰值）。 */
    private static final Map<Object, Long> BOX_BUFFERS = new WeakHashMap<>();
    /** BoxUtil PublicFBO 实例 → [纹理字节, 纹理数, renderbuffer字节]。 */
    private static final Map<Object, long[]> PUBLIC_FBOS = new WeakHashMap<>();
    /** 模组直传/屏幕 RT/受管贴图纹理：纹理 id → [字节, 分类序号]（upTex/screenRT/gameTex 共用，按 id 替换）。 */
    private static final Map<Integer, long[]> TRACKED_TEXTURES = new HashMap<>();
    /** 缓冲对象：buffer id → 字节（重复 glBufferData/glBufferStorage 同 id 按替换计）。 */
    private static final Map<Integer, Long> TRACKED_BUFFERS = new HashMap<>();

    /** GraphicsLib 内部渲染分辨率缓存（getInternalWidth/Height 返回值）。 */
    private static int gfxlibInternalWidth;
    private static int gfxlibInternalHeight;
    /** BoxUtil 屏幕缩放分辨率缓存（getScreenScaleWidth/Height 返回值）。 */
    private static int boxScaleWidth;
    private static int boxScaleHeight;

    private static boolean gfxlibSizeWarned;
    private static boolean boxSizeWarned;

    /** 未知纹理/缓冲绑定 target 告警去重（每种 target 只 WARN 一次）。 */
    private static final Set<Integer> UNKNOWN_BINDING_WARNED = ConcurrentHashMap.newKeySet();

    private GlLedgerHooks() {
    }

    /** 游戏 {@code nextPowerOfTwo} 的等价实现（最小 2），与游戏字节码逐行一致。 */
    public static int nextPowerOfTwo(final int value) {
        int pot = 2;
        while (pot < value) {
            pot *= 2;
        }
        return pot;
    }

    /**
     * 游戏 {@code FrameBufferObject.createFramebuffer(IIIIZ)Z} 返回 true 时调用。
     * 颜色纹理为 POT 补齐的 RGBA8；mipmaps 时按完整链 ×4/3。
     */
    public static void noteVanillaFboCreated(final int fboId, final int width, final int height,
                                             final boolean mipmaps) {
        final long base = (long) nextPowerOfTwo(width) * nextPowerOfTwo(height) * 4L;
        final long bytes = mipmaps ? GlMemoryLedger.withMipmaps(base) : base;
        synchronized (LOCK) {
            final Long previous = VANILLA_FBO.put(fboId, bytes);
            if (previous != null) {
                GlMemoryLedger.remove(GlMemoryLedger.Category.FBO_TEX, previous, 1);
            }
        }
        GlMemoryLedger.add(GlMemoryLedger.Category.FBO_TEX, bytes, 1);
    }

    /** 游戏 {@code FrameBufferObject.deleteFramebuffer()V} 进入时调用（此时 fboId 未清零）。 */
    public static void noteVanillaFboDeleted(final int fboId) {
        final Long bytes;
        synchronized (LOCK) {
            bytes = VANILLA_FBO.remove(fboId);
        }
        if (bytes != null) {
            GlMemoryLedger.remove(GlMemoryLedger.Category.FBO_TEX, bytes, 1);
        }
    }

    /** GraphicsLib {@code ShaderLib.getInternalWidth()I} 返回值缓存。 */
    public static void noteShaderLibInternalWidth(final int width) {
        synchronized (LOCK) {
            gfxlibInternalWidth = width;
        }
    }

    /** GraphicsLib {@code ShaderLib.getInternalHeight()I} 返回值缓存。 */
    public static void noteShaderLibInternalHeight(final int height) {
        synchronized (LOCK) {
            gfxlibInternalHeight = height;
        }
    }

    /**
     * GraphicsLib {@code ShaderLib.init()V} 返回时调用：登记 screenTex（RGB8+mipmap）、
     * foregroundBufferTex（RGBA8+mipmap）、auxiliaryBufferTex（RGBA8/RGBA16+mipmap）
     * 三张 RTT 尺寸纹理。无删除路径，按纹理 id 跟踪，重复 init 自动替换旧值。
     */
    public static void noteShaderLibInit(final boolean shadersAllowed, final boolean buffersAllowed,
                                         final boolean aux64Bit, final int rttWidth,
                                         final int rttHeight, final int screenTex,
                                         final int foregroundTex, final int auxTex) {
        synchronized (LOCK) {
            gfxlibInternalWidth = rttWidth;
            gfxlibInternalHeight = rttHeight;
        }
        if (shadersAllowed && screenTex != 0) {
            // GL_RGB8 + glGenerateMipmap
            replaceGfxlibTexture(screenTex,
                    GlMemoryLedger.withMipmaps((long) rttWidth * rttHeight * 3L));
        }
        if (shadersAllowed && buffersAllowed) {
            if (foregroundTex != 0) {
                // GL_RGBA8 + glGenerateMipmap
                replaceGfxlibTexture(foregroundTex,
                        GlMemoryLedger.withMipmaps((long) rttWidth * rttHeight * 4L));
            }
            if (auxTex != 0) {
                // auxiliaryBuffer64Bit ? GL_RGBA16 : GL_RGBA8，均 + glGenerateMipmap
                replaceGfxlibTexture(auxTex, GlMemoryLedger.withMipmaps(
                        (long) rttWidth * rttHeight * (aux64Bit ? 8L : 4L)));
            }
        }
    }

    /**
     * GraphicsLib {@code ShaderLib.makeFramebuffer(IIIII)I} 返回非 0 时调用：
     * 每个 FBO 附带一张 STENCIL_INDEX8（1 字节/像素）renderbuffer。
     * GraphicsLib 无 renderbuffer 删除路径，<b>只计分配峰值</b>。
     */
    public static void noteShaderLibRenderbuffer(final int fboId, final int width, final int height) {
        final long bytes = (long) width * height;
        synchronized (LOCK) {
            GFXLIB_RENDERBUFFERS.put(fboId, bytes);
        }
        GlMemoryLedger.add(GlMemoryLedger.Category.RBO, bytes, 1);
    }

    /**
     * GraphicsLib {@code LightShader} 构造器返回时调用，按实际非零纹理 id 计量：
     * lightTex（R32F 4096×1）、normalTex（RGB8 RTT+mipmap）、hdrTex（RGB16 RTT+mipmap）、
     * hdrTex2/3（RGB8，RTT/2^(bloomMips-1)+mipmap）。
     */
    public static void noteLightShaderCreated(final Object self, final int lightTex,
                                              final int normalTex, final int hdrTex,
                                              final int hdrTex2, final int hdrTex3,
                                              final int bloomMips) {
        final int rttW;
        final int rttH;
        synchronized (LOCK) {
            rttW = gfxlibInternalWidth;
            rttH = gfxlibInternalHeight;
        }
        long bytes = 0L;
        int objects = 0;
        if (lightTex != 0) {
            bytes += 4096L * 4L;
            objects++;
        }
        if ((normalTex != 0 || hdrTex != 0 || hdrTex2 != 0 || hdrTex3 != 0) && (rttW <= 0 || rttH <= 0)) {
            if (!gfxlibSizeWarned) {
                gfxlibSizeWarned = true;
                LOGGER.warn("[SSOptimizer] glLedger: LightShader 纹理计量时 ShaderLib 内部分辨率未知，"
                        + "尺寸相关条目跳过");
            }
        } else {
            if (normalTex != 0) {
                bytes += GlMemoryLedger.withMipmaps((long) rttW * rttH * 3L);
                objects++;
            }
            if (hdrTex != 0) {
                bytes += GlMemoryLedger.withMipmaps((long) rttW * rttH * 6L);
                objects++;
            }
            final int shift = Math.max(bloomMips - 1, 0);
            final long bloomBytes = GlMemoryLedger.withMipmaps(
                    (long) (rttW >> shift) * (rttH >> shift) * 3L);
            if (hdrTex2 != 0) {
                bytes += bloomBytes;
                objects++;
            }
            if (hdrTex3 != 0) {
                bytes += bloomBytes;
                objects++;
            }
        }
        if (bytes == 0L) {
            return;
        }
        synchronized (LOCK) {
            LIGHT_SHADERS.put(self, new long[]{bytes, objects});
        }
        GlMemoryLedger.add(GlMemoryLedger.Category.GFXLIB_TEX, bytes, objects);
    }

    /** GraphicsLib {@code LightShader.destroy()V} 进入时调用，对称移除实例登记。 */
    public static void noteLightShaderDestroyed(final Object self) {
        final long[] entry;
        synchronized (LOCK) {
            entry = LIGHT_SHADERS.remove(self);
        }
        if (entry != null) {
            GlMemoryLedger.remove(GlMemoryLedger.Category.GFXLIB_TEX, entry[0], (int) entry[1]);
        }
    }

    /** BoxUtil {@code ShaderCore.getScreenScaleWidth()I} 返回值缓存。 */
    public static void noteBoxScaleWidth(final int width) {
        synchronized (LOCK) {
            boxScaleWidth = width;
        }
    }

    /** BoxUtil {@code ShaderCore.getScreenScaleHeight()I} 返回值缓存。 */
    public static void noteBoxScaleHeight(final int height) {
        synchronized (LOCK) {
            boxScaleHeight = height;
        }
    }

    /**
     * BoxUtil {@code BUtil_RenderingBuffer} 构造器返回时调用：
     * 每个完工层（finished[i]）的附件纹理为全分辨率（scaleSize[0]）glTexStorage2D
     * （levels=1，无 mipmap）；bloom ping-pong 1..currLayerCount-1 号位为 scaleSize[l]
     * 尺寸、格式取 _INTERNAL_FORMAT[1][0]（0 号位是 texID[1][0] 的别名，不重复计）；
     * RBO 为全分辨率 DEPTH_COMPONENT16。
     * bloom 纹理不按 finished 门控：GL 不支持的早退路径会泄漏它们，计入更贴近真实驻留
     * （layer0 完工检查失败这一罕见错误路径下会高估一条 bloom 链）。
     * <b>只计分配峰值</b>：delete(int) 的逐层部分删除 + bloom 别名语义无法无歧义对称。
     */
    public static void noteBoxRenderingBufferCreated(final Object self, final int[][] texId,
                                                     final int[][] internalFormat,
                                                     final int[][] scaleSize,
                                                     final int[] bloomPingPongTex,
                                                     final int currLayerCount,
                                                     final boolean[] finished, final int rbo) {
        if (scaleSize == null || scaleSize.length == 0 || scaleSize[0] == null
                || scaleSize[0].length < 2) {
            return;
        }
        final long fullPixels = (long) scaleSize[0][0] * scaleSize[0][1];
        long bytes = 0L;
        int objects = 0;
        if (texId != null && internalFormat != null && finished != null) {
            for (int layer = 0; layer < texId.length && layer < internalFormat.length
                    && layer < finished.length; layer++) {
                if (!finished[layer] || texId[layer] == null || internalFormat[layer] == null) {
                    continue;
                }
                for (int att = 0; att < texId[layer].length && att < internalFormat[layer].length;
                     att++) {
                    if (texId[layer][att] != 0) {
                        bytes += fullPixels
                                * GlMemoryLedger.bytesPerPixel(internalFormat[layer][att]);
                        objects++;
                    }
                }
            }
        }
        if (bloomPingPongTex != null && internalFormat != null && internalFormat.length > 1
                && internalFormat[1] != null && internalFormat[1].length > 0) {
            final int bloomBpp = GlMemoryLedger.bytesPerPixel(internalFormat[1][0]);
            final int layers = Math.min(currLayerCount, bloomPingPongTex.length);
            for (int l = 1; l < layers && l < scaleSize.length; l++) {
                if (bloomPingPongTex[l] != 0 && scaleSize[l] != null && scaleSize[l].length >= 2) {
                    bytes += (long) scaleSize[l][0] * scaleSize[l][1] * bloomBpp;
                    objects++;
                }
            }
        }
        if (bytes > 0L) {
            synchronized (LOCK) {
                BOX_BUFFERS.put(self, bytes);
            }
            GlMemoryLedger.add(GlMemoryLedger.Category.BOX_TEX, bytes, objects);
        }
        // RBO 只在 layer 0 的迭代内创建，layer 0 未完工时构造器内部 delete(0) 已将其删除
        if (rbo > 0 && finished != null && finished.length > 0 && finished[0]) {
            GlMemoryLedger.add(GlMemoryLedger.Category.RBO, fullPixels * 2L, 1);
        }
    }

    /**
     * BoxUtil {@code PublicFBO} 构造器返回时调用：texID 全部附件纹理为屏幕缩放分辨率、
     * 内部格式取 FORMAT[i][0]；RBO 为同分辨率 DEPTH24_STENCIL8（4 字节/像素）。
     * 构造失败（finished=false）时构造器内部已 delete()，不入账。
     */
    public static void notePublicFboCreated(final Object self, final int[] texId,
                                            final int[][] format, final boolean finished,
                                            final int rbo) {
        if (!finished || texId == null || format == null) {
            return;
        }
        final int w;
        final int h;
        synchronized (LOCK) {
            w = boxScaleWidth;
            h = boxScaleHeight;
        }
        if (w <= 0 || h <= 0) {
            if (!boxSizeWarned) {
                boxSizeWarned = true;
                LOGGER.warn("[SSOptimizer] glLedger: PublicFBO 计量时 BoxUtil 屏幕缩放分辨率未知，跳过");
            }
            return;
        }
        final long pixels = (long) w * h;
        long texBytes = 0L;
        int objects = 0;
        for (int i = 0; i < texId.length && i < format.length; i++) {
            if (texId[i] != 0 && format[i] != null && format[i].length > 0) {
                texBytes += pixels * GlMemoryLedger.bytesPerPixel(format[i][0]);
                objects++;
            }
        }
        final long rboBytes = rbo > 0 ? pixels * 4L : 0L;
        if (texBytes == 0L && rboBytes == 0L) {
            return;
        }
        synchronized (LOCK) {
            PUBLIC_FBOS.put(self, new long[]{texBytes, objects, rboBytes});
        }
        if (texBytes > 0L) {
            GlMemoryLedger.add(GlMemoryLedger.Category.BOX_TEX, texBytes, objects);
        }
        if (rboBytes > 0L) {
            GlMemoryLedger.add(GlMemoryLedger.Category.RBO, rboBytes, 1);
        }
    }

    /** BoxUtil {@code PublicFBO.delete()V} 进入时调用，对称移除实例登记。 */
    public static void notePublicFboDeleted(final Object self) {
        final long[] entry;
        synchronized (LOCK) {
            entry = PUBLIC_FBOS.remove(self);
        }
        if (entry != null) {
            if (entry[0] > 0L) {
                GlMemoryLedger.remove(GlMemoryLedger.Category.BOX_TEX, entry[0], (int) entry[1]);
            }
            if (entry[2] > 0L) {
                GlMemoryLedger.remove(GlMemoryLedger.Category.RBO, entry[2], 1);
            }
        }
    }

    // ---------- upTex/screenRT/vbo 计量核心（纯记账，可单测） ----------

    /**
     * 纹理分配字节计算：宽×高×{@link GlMemoryLedger#bytesPerPixel(int)}；
     * {@code levels > 1}（glTexStorage 分配完整 mipmap 链）按 ×4/3 近似。
     * 已知不准：glTexImage 系按单次调用的 level 0 分配计——本批埋点目标类
     * （TextureManager/LegacyNormalMapHelper/各 RT 旁路）实际只用 level 0 分配；
     * 若同 id 分级独立分配会按 id 互相顶替（替换语义），口径偏保守。
     */
    public static long texImageBytes(final int internalFormat, final int width,
                                     final int height, final int levels) {
        if (width <= 0 || height <= 0) {
            return 0L;
        }
        final long base = (long) width * height * GlMemoryLedger.bytesPerPixel(internalFormat);
        return levels > 1 ? GlMemoryLedger.withMipmaps(base) : base;
    }

    /**
     * 登记一次纹理分配（upTex/screenRT 共用）。按纹理 id 跟踪：同 id 重复分配
     * （尺寸变化重分配、ensure* 幂等重建）按替换计，先减旧值再加新值；
     * id 不可得（<= 0）时退化为只加不减的毛计量。
     */
    public static void noteTextureBytes(final GlMemoryLedger.Category category, final int texId,
                                        final long bytes) {
        if (bytes <= 0L) {
            return;
        }
        if (texId <= 0) {
            GlMemoryLedger.add(category, bytes, 1);
            return;
        }
        final long[] previous;
        synchronized (LOCK) {
            previous = TRACKED_TEXTURES.put(texId, new long[]{bytes, category.ordinal()});
        }
        if (previous != null) {
            GlMemoryLedger.remove(GlMemoryLedger.Category.values()[(int) previous[1]],
                    previous[0], 1);
        }
        GlMemoryLedger.add(category, bytes, 1);
    }

    /** 纹理删除对称减量（glDeleteTextures 路径存在时由转发钩子调用）。 */
    public static void noteTextureFreed(final int texId) {
        final long[] entry;
        synchronized (LOCK) {
            entry = TRACKED_TEXTURES.remove(texId);
        }
        if (entry != null) {
            GlMemoryLedger.remove(GlMemoryLedger.Category.values()[(int) entry[1]], entry[0], 1);
        }
    }

    // ---------- gameTex 计量（LazyTextureManager/ShipWeaponAtlas 上传路径直调，可单测） ----------

    /**
     * 受管贴图未压缩上传（{@code glTexImage2D} RGBA8）的驻留字节数：宽×高×4；
     * {@code mipmaps}（GL_GENERATE_MIPMAP=1，生成完整 mip 链）按 ×4/3 近似。
     */
    public static long gameTexBytesForUpload(final boolean mipmaps, final int width,
                                             final int height) {
        if (width <= 0 || height <= 0) {
            return 0L;
        }
        final long base = (long) width * height * 4L;
        return mipmaps ? GlMemoryLedger.withMipmaps(base) : base;
    }

    /**
     * SSOBC 压缩容器的精确上传字节数：各级 {@code dataLength} 求和，即逐次
     * {@code glCompressedTexImage2D} 提交的块数据总量（等价于 BC1=8B/块、
     * BC3/BC7=16B/块的块公式精确值，含完整 mip 链）。
     */
    public static long compressedContainerBytes(final SsobcContainer container) {
        long total = 0L;
        for (final SsobcContainer.Level level : container.levels()) {
            total += level.dataLength();
        }
        return total;
    }

    /**
     * 受管游戏贴图（gameTex 分类）入账：LazyTextureManager 各上传路径在提交上传后调用。
     * <p>
     * 计量口径：RT 渲染线程模式下上传调用经 bridge 入队，记账点为「已提交上传」而非
     * 「GPU 完成」；同 id 重指定存储（热重传升级为压缩形态）按替换计——GL 语义上
     * texImage 系重定义即释放旧存储，无双驻留窗口。与驱逐/上下文重建路径的
     * {@link #noteGameTexFreed(int)} 配对。游戏设置开启原始延迟加载模式时贴图走游戏
     * 自身 deferredLoading，不在本口径内。
     */
    public static void noteGameTexBytes(final int textureId, final long bytes) {
        noteTextureBytes(GlMemoryLedger.Category.GAME_TEX, textureId, bytes);
    }

    /** 受管游戏贴图删除（闲置驱逐/旧上下文销毁）对称减量；未登记过的 id 为无害空操作。 */
    public static void noteGameTexFreed(final int textureId) {
        noteTextureFreed(textureId);
    }

    /**
     * 登记一次缓冲对象分配（vbo 分类）。按 buffer id 跟踪，同 id 重复
     * glBufferData/glBufferStorage 按替换计（GL 语义即旧存储被释放）；
     * id 不可得（<= 0）时退化为只加不减的毛计量。
     */
    public static void noteBufferBytes(final int bufferId, final long bytes) {
        if (bytes <= 0L) {
            return;
        }
        if (bufferId <= 0) {
            GlMemoryLedger.add(GlMemoryLedger.Category.VBO, bytes, 1);
            return;
        }
        final Long previous;
        synchronized (LOCK) {
            previous = TRACKED_BUFFERS.put(bufferId, bytes);
        }
        if (previous != null) {
            GlMemoryLedger.remove(GlMemoryLedger.Category.VBO, previous, 1);
        }
        GlMemoryLedger.add(GlMemoryLedger.Category.VBO, bytes, 1);
    }

    /** 缓冲对象删除对称减量（glDeleteBuffers 路径存在时由转发钩子/Mixin 调用）。 */
    public static void noteBufferFreed(final int bufferId) {
        final Long bytes;
        synchronized (LOCK) {
            bytes = TRACKED_BUFFERS.remove(bufferId);
        }
        if (bytes != null) {
            GlMemoryLedger.remove(GlMemoryLedger.Category.VBO, bytes, 1);
        }
    }

    // ---------- upTex/screenRT/vbo GL 转发钩子（ASM 重定向目标，签名与被替换调用一致） ----------

    /** 替换 {@code GL11.glTexImage1D}（BoxUtil TextureManager），UPTEX 分类。 */
    public static void upTexImage1D(final int target, final int level, final int internalformat,
                                    final int width, final int border, final int format,
                                    final int type, final java.nio.ByteBuffer pixels) {
        org.lwjgl.opengl.GL11.glTexImage1D(target, level, internalformat, width, border, format,
                type, pixels);
        noteTextureBytes(GlMemoryLedger.Category.UPTEX, boundTextureId(target),
                texImageBytes(internalformat, width, 1, 0));
    }

    /** 替换 {@code GL11.glTexImage2D}（TextureManager/LegacyNormalMapHelper），UPTEX 分类。 */
    public static void upTexImage2D(final int target, final int level, final int internalformat,
                                    final int width, final int height, final int border,
                                    final int format, final int type,
                                    final java.nio.ByteBuffer pixels) {
        org.lwjgl.opengl.GL11.glTexImage2D(target, level, internalformat, width, height, border,
                format, type, pixels);
        noteTextureBytes(GlMemoryLedger.Category.UPTEX, boundTextureId(target),
                texImageBytes(internalformat, width, height, 0));
    }

    /** 替换 {@code GL42.glTexStorage1D}（TextureManager），UPTEX 分类。 */
    public static void upTexStorage1D(final int target, final int levels,
                                      final int internalformat, final int width) {
        org.lwjgl.opengl.GL42.glTexStorage1D(target, levels, internalformat, width);
        noteTextureBytes(GlMemoryLedger.Category.UPTEX, boundTextureId(target),
                texImageBytes(internalformat, width, 1, levels));
    }

    /** 替换 {@code GL42.glTexStorage2D}（TextureManager/LegacyNormalMapHelper），UPTEX 分类。 */
    public static void upTexStorage2D(final int target, final int levels,
                                      final int internalformat, final int width,
                                      final int height) {
        org.lwjgl.opengl.GL42.glTexStorage2D(target, levels, internalformat, width, height);
        noteTextureBytes(GlMemoryLedger.Category.UPTEX, boundTextureId(target),
                texImageBytes(internalformat, width, height, levels));
    }

    /** 替换 {@code ARBTextureStorage.glTexStorage2D}（LegacyNormalMapHelper），UPTEX 分类。 */
    public static void upTexStorage2DARB(final int target, final int levels,
                                         final int internalformat, final int width,
                                         final int height) {
        org.lwjgl.opengl.ARBTextureStorage.glTexStorage2D(target, levels, internalformat, width,
                height);
        noteTextureBytes(GlMemoryLedger.Category.UPTEX, boundTextureId(target),
                texImageBytes(internalformat, width, height, levels));
    }

    /** 替换 {@code GL11.glDeleteTextures}（TextureManager.deleteTexture/deleteAutoGenNormal）。 */
    public static void upTexDeleteTexture(final int texture) {
        org.lwjgl.opengl.GL11.glDeleteTextures(texture);
        noteTextureFreed(texture);
    }

    /** 替换 {@code GL11.glTexImage2D}（各屏幕尺寸 RT 旁路），SCREEN_RT 分类。 */
    public static void rtTexImage2D(final int target, final int level, final int internalformat,
                                    final int width, final int height, final int border,
                                    final int format, final int type,
                                    final java.nio.ByteBuffer pixels) {
        org.lwjgl.opengl.GL11.glTexImage2D(target, level, internalformat, width, height, border,
                format, type, pixels);
        noteTextureBytes(GlMemoryLedger.Category.SCREEN_RT, boundTextureId(target),
                texImageBytes(internalformat, width, height, 0));
    }

    /** 替换 {@code GL11.glCopyTexImage2D}（BoxConfigGUI.renderInUICoords），SCREEN_RT 分类。 */
    public static void rtCopyTexImage2D(final int target, final int level,
                                        final int internalformat, final int x, final int y,
                                        final int width, final int height, final int border) {
        org.lwjgl.opengl.GL11.glCopyTexImage2D(target, level, internalformat, x, y, width, height,
                border);
        noteTextureBytes(GlMemoryLedger.Category.SCREEN_RT, boundTextureId(target),
                texImageBytes(internalformat, width, height, 0));
    }

    /** 替换 {@code GL11.glDeleteTextures}（Moci/No101/ASTD cleanup 路径）。 */
    public static void rtDeleteTexture(final int texture) {
        org.lwjgl.opengl.GL11.glDeleteTextures(texture);
        noteTextureFreed(texture);
    }

    /** 替换 {@code GL15.glBufferData(int,long,int)}（BUtil_InstanceDataMemoryPool），VBO 分类。 */
    public static void vboBufferData(final int target, final long size, final int usage) {
        org.lwjgl.opengl.GL15.glBufferData(target, size, usage);
        noteBufferBytes(boundBufferId(target), size);
    }

    /** 替换 {@code GL15.glBufferData(int,FloatBuffer,int)}（ParticleEngine），VBO 分类。 */
    public static void vboBufferData(final int target, final java.nio.FloatBuffer data,
                                     final int usage) {
        org.lwjgl.opengl.GL15.glBufferData(target, data, usage);
        noteBufferBytes(boundBufferId(target), (long) data.remaining() * 4L);
    }

    /** 替换 {@code GL44.glBufferStorage(int,long,int)}（BUtil_InstanceDataMemoryPool），VBO 分类。 */
    public static void vboBufferStorage(final int target, final long size, final int flags) {
        org.lwjgl.opengl.GL44.glBufferStorage(target, size, flags);
        noteBufferBytes(boundBufferId(target), size);
    }

    /** 替换 {@code GL15.glDeleteBuffers}（BUtil_InstanceDataMemoryPool），VBO 对称减量。 */
    public static void vboDeleteBuffer(final int buffer) {
        org.lwjgl.opengl.GL15.glDeleteBuffers(buffer);
        noteBufferFreed(buffer);
    }

    /** 纹理 target → 当前绑定纹理 id；未知 target 告警一次并返回 -1（调用方退化为毛计量）。 */
    private static int boundTextureId(final int target) {
        final int binding;
        switch (target) {
            case 3552:  // GL_TEXTURE_1D
                binding = 32872; // GL_TEXTURE_BINDING_1D
                break;
            case 3553:  // GL_TEXTURE_2D
                binding = 32873; // GL_TEXTURE_BINDING_2D
                break;
            case 32879: // GL_TEXTURE_3D
                binding = 32874; // GL_TEXTURE_BINDING_3D
                break;
            default:
                warnUnknownBinding("纹理", target);
                return -1;
        }
        return org.lwjgl.opengl.GL11.glGetInteger(binding);
    }

    /** 缓冲 target → 当前绑定 buffer id；未知 target 告警一次并返回 -1。 */
    private static int boundBufferId(final int target) {
        final int binding;
        switch (target) {
            case 34962: // GL_ARRAY_BUFFER
                binding = 34964; // GL_ARRAY_BUFFER_BINDING
                break;
            case 34963: // GL_ELEMENT_ARRAY_BUFFER
                binding = 34965; // GL_ELEMENT_ARRAY_BUFFER_BINDING
                break;
            case 35345: // GL_UNIFORM_BUFFER
                binding = 35368; // GL_UNIFORM_BUFFER_BINDING
                break;
            case 37074: // GL_SHADER_STORAGE_BUFFER
                binding = 37075; // GL_SHADER_STORAGE_BUFFER_BINDING
                break;
            default:
                warnUnknownBinding("缓冲", target);
                return -1;
        }
        return org.lwjgl.opengl.GL11.glGetInteger(binding);
    }

    private static void warnUnknownBinding(final String kind, final int target) {
        if (UNKNOWN_BINDING_WARNED.add(target)) {
            LOGGER.warn("[SSOptimizer] glLedger 未知" + kind + "绑定 target " + target
                    + "，本次分配只计增量不做 id 跟踪");
        }
    }

    /** 重置全部跟踪状态（仅供测试）。 */
    public static void resetForTesting() {
        synchronized (LOCK) {
            VANILLA_FBO.clear();
            GFXLIB_TEXTURES.clear();
            GFXLIB_RENDERBUFFERS.clear();
            LIGHT_SHADERS.clear();
            BOX_BUFFERS.clear();
            PUBLIC_FBOS.clear();
            TRACKED_TEXTURES.clear();
            TRACKED_BUFFERS.clear();
            UNKNOWN_BINDING_WARNED.clear();
            gfxlibInternalWidth = 0;
            gfxlibInternalHeight = 0;
            boxScaleWidth = 0;
            boxScaleHeight = 0;
            gfxlibSizeWarned = false;
            boxSizeWarned = false;
        }
    }

    private static void replaceGfxlibTexture(final int texId, final long bytes) {
        final Long previous;
        synchronized (LOCK) {
            previous = GFXLIB_TEXTURES.put(texId, bytes);
        }
        if (previous != null) {
            GlMemoryLedger.remove(GlMemoryLedger.Category.GFXLIB_TEX, previous, 1);
        }
        GlMemoryLedger.add(GlMemoryLedger.Category.GFXLIB_TEX, bytes, 1);
    }
}
