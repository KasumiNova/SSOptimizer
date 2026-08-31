package github.kasuminova.ssoptimizer.common.render.atlas;

import com.fs.starfarer.loading.LoadingUtils;
import com.fs.starfarer.loading.ShipHullSpecStore;
import com.fs.starfarer.loading.WeaponSpecStore;
import com.fs.starfarer.loading.specs.BaseWeaponSpec;
import com.fs.starfarer.loading.specs.BeamWeaponSpec;
import com.fs.starfarer.loading.specs.MissileSpec;
import com.fs.starfarer.loading.specs.ProjectileWeaponSpec;
import com.fs.starfarer.loading.specs.ShipHullSpec;
import com.fs.util.ResourceLoader;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import github.kasuminova.ssoptimizer.common.concurrent.VtWorkers;
import github.kasuminova.ssoptimizer.common.loading.GlLedgerHooks;
import github.kasuminova.ssoptimizer.common.loading.GlMemoryLedger;
import github.kasuminova.ssoptimizer.common.loading.LazyTextureManager;
import github.kasuminova.ssoptimizer.common.loading.TexturePixelConversionResult;
import github.kasuminova.ssoptimizer.common.loading.TexturePixelConverter;
import github.kasuminova.ssoptimizer.common.loading.TextureUploadHelper;
import org.apache.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * 舰船/武器贴图动态图集。
 * <p>
 * 加载期（{@code ResourceLoaderState.init} 返回点，主线程持 GL 上下文）把全部舰船
 * 船体图与武器系列贴图（炮塔/硬点/底衬/辉光/枪管/动画帧，以及导弹船体与辉光）
 * shelf 装箱进若干图集页（期望 8192²，按 {@code GL_MAX_TEXTURE_SIZE} 收敛；按船体/武器/弹体 id
 * 亲和分组排布以提高同页命中率）并上传 GPU；{@code SpriteAtlasMixin} 在
 * {@code Sprite.setTexture} 尾部把 UV 四字段重映射进图集区域，
 * 渲染取 id 走 render 域 {@code AtlasTextureResolver}（SpriteMixin 覆写方法内
 * 图集命中返回图集页 id），{@code LazyTextureManager.bindTexture} 把已入图集路径的
 * bind 重定向到图集纹理。效果：同页贴图共享同一 GL 纹理 id，SpriteBatch 合批率
 * 与 bind 次数显著改善，且原始贴图不再单独上传（节省显存）。
 * <p>
 * 防渗色：每张贴图四边 16px 边缘复制 padding + 图集生成 mipmap。
 * <p>
 * 已知边界：
 * <ul>
 *   <li>与 LazyTextureManager 原始延迟加载模式（{@code starfarer.settings} 级开关）
 *       不兼容——该模式下贴图元数据在 setTexture 时不可用，构建直接跳过；</li>
 *   <li>图集纹理 id 绑定到构建时的 GL 上下文，运行期显示模式重建上下文后需重启
 *       （与原版大纹理行为一致，初版不做上下文热重建）；</li>
 *   <li>光束 core/fringe 与弹丸贴图使用平铺/自定义 UV，不入图集；</li>
 *   <li>settings.json graphics 段引用的贴图（模组通用贴图注册表，消费方多为
 *       0..1 裸 UV 全图采样）构建期整体排除；</li>
 *   <li>{@code getTextureId} 不感知图集（纯惰性上传语义）：模组经
 *       {@code SpriteAPI.getTextureId()} 取 id 的裸 UV 消费者始终拿到独立纹理，
 *       图集 id 只对 Sprite 渲染路径（UV 已重映射）可见。已知边界：模组若捕获
 *       Sprite 的图集重映射 UV 又配对该独立 id 采样，会得到错误子区域
 *       （当前模组集未实证此形态，出现时需按个案适配）。</li>
 * </ul>
 * 开关：{@code -Dssoptimizer.atlas.shipweapon=false} 关闭（默认开启）；
 * {@code -Dssoptimizer.atlas.shipweapon.dumpdir=<dir>} 导出每页 PNG 供检查空间利用率。
 */
public final class ShipWeaponAtlas {
    public static final String ENABLED_PROPERTY = "ssoptimizer.atlas.shipweapon";
    public static final String DUMP_DIR_PROPERTY = "ssoptimizer.atlas.shipweapon.dumpdir";

    private static final Logger LOGGER = Logger.getLogger(ShipWeaponAtlas.class);

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty(ENABLED_PROPERTY, "true"));
    private static final int DESIRED_ATLAS_SIZE = 8192;
    private static final int PADDING = 16;

    private static final Map<String, Region> REGIONS = new ConcurrentHashMap<>();
    private static volatile boolean built = false;

    /**
     * 单张贴图在图集中的区域（GL 空间，左下原点）。
     *
     * @param textureId 图集页 GL 纹理 id
     * @param atlasSize 图集页边长
     * @param x         内容区域原点 X（GL 空间）
     * @param y         内容区域原点 Y（GL 空间）
     */
    public record Region(int textureId, int atlasSize, int x, int y) {
    }

    private ShipWeaponAtlas() {
    }

    /**
     * 查询贴图路径的图集区域。
     *
     * @param texturePath 贴图资源路径（{@code TextureObject.getTexturePath()}）
     * @return 已入图集返回区域；未入图集/图集未构建返回 null
     */
    public static Region lookup(final String texturePath) {
        if (texturePath == null || REGIONS.isEmpty()) {
            return null;
        }
        return REGIONS.get(texturePath);
    }

    /**
     * 构建图集。必须在主线程且 GL 上下文当前时调用（加载完成、进标题前）。
     * 幂等：重复调用直接返回。
     */
    public static void build() {
        if (built) {
            return;
        }
        built = true;
        if (!ENABLED) {
            LOGGER.info("[SSOptimizer] Ship/weapon texture atlas disabled via -D" + ENABLED_PROPERTY + "=false");
            return;
        }
        if (LazyTextureManager.isOriginalLazyModeEnabled()) {
            LOGGER.info("[SSOptimizer] Ship/weapon texture atlas skipped: original lazy loading mode "
                    + "has no texture metadata at setTexture time");
            return;
        }

        final long startNanos = System.nanoTime();
        final Map<String, String> pathAffinity = collectSpritePaths();
        final Map<String, BufferedImage> images = decodeAll(pathAffinity.keySet());
        if (images.isEmpty()) {
            LOGGER.warn("[SSOptimizer] Ship/weapon texture atlas: no sprite decoded, atlas disabled");
            return;
        }

        final List<AtlasPacker.Entry> entries = new ArrayList<>(images.size());
        images.forEach((path, image) -> entries.add(
                new AtlasPacker.Entry(path, image.getWidth(), image.getHeight(),
                        pathAffinity.getOrDefault(path, ""))));
        final int pageSize = resolvePageSize();
        final AtlasPacker.Result packed = AtlasPacker.pack(entries, pageSize, PADDING);
        final java.nio.file.Path dumpDir = resolveDumpDir();

        int regionCount = 0;
        long contentArea = 0L;
        long cellArea = 0L;
        for (AtlasPacker.Page page : packed.pages()) {
            final int textureId = composeAndUpload(page, images, pageSize, dumpDir);
            LOGGER.info("[SSOptimizer] Ship/weapon texture atlas page " + page.index()
                    + " uploaded: textureId=" + textureId + " regions=" + page.placements().size());
            for (AtlasPacker.Placement placement : page.placements()) {
                // 图像空间（左上原点）→ GL 空间（左下原点）：gY = atlasSize - y - height
                REGIONS.put(placement.path(), new Region(
                        textureId, pageSize, placement.x(), pageSize - placement.y() - placement.height()));
                regionCount++;
                contentArea += (long) placement.width() * placement.height();
                cellArea += (long) (placement.width() + PADDING * 2) * (placement.height() + PADDING * 2);
            }
        }
        images.clear();

        final double totalArea = (double) packed.pages().size() * pageSize * pageSize;
        LOGGER.info(String.format(
                "[SSOptimizer] Ship/weapon texture atlas built: %d regions in %d page(s) of %d², %d skipped (oversize),"
                        + " fill=%.1f%% (cell), content=%.1f%%, %d ms",
                regionCount, packed.pages().size(), pageSize, packed.skipped().size(),
                cellArea / totalArea * 100.0, contentArea / totalArea * 100.0,
                (System.nanoTime() - startNanos) / 1_000_000L));
    }

    /**
     * 解析图集页导出目录：{@code -Dssoptimizer.atlas.shipweapon.dumpdir=<dir>} 设置后，
     * 构建时把每页图集写成 PNG 供人工检查空间利用率；未设置返回 null（不导出）。
     */
    private static java.nio.file.Path resolveDumpDir() {
        final String configured = System.getProperty(DUMP_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return java.nio.file.Paths.get(configured.trim());
    }

    /**
     * 解析图集页边长：期望 8192（近十年独显/核显普遍支持 16384），
     * 受 {@code GL_MAX_TEXTURE_SIZE} 限制时收敛到硬件上限并记日志。
     * 调用方必须持有 OpenGL 上下文。
     */
    private static int resolvePageSize() {
        final int maxTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        final int pageSize = Math.min(DESIRED_ATLAS_SIZE, maxTextureSize);
        if (pageSize < DESIRED_ATLAS_SIZE) {
            LOGGER.warn("[SSOptimizer] Ship/weapon texture atlas: GL_MAX_TEXTURE_SIZE=" + maxTextureSize
                    + ", page size limited to " + pageSize);
        }
        return pageSize;
    }

    /**
     * 枚举舰船/武器系列贴图路径（口径与 {@code ResourceLoaderState.queueShipAndWeaponSprites}
     * 一致，去掉光束与弹丸贴图），并为每个路径标注亲和分组键：
     * 舰船按船体 id、武器按武器 id、导弹按弹体 id 分组。同组贴图在图集内连续排布，
     * 渲染一艘船/一件武器的全部贴图时落在同一页，提高 bind 去重与合批命中率。
     *
     * @return 贴图路径 → 亲和分组键（同一路径被多个组引用时先到先得，保证确定性）
     */
    private static Map<String, String> collectSpritePaths() {
        final Map<String, String> paths = new java.util.LinkedHashMap<>();
        for (String hullId : ShipHullSpecStore.getIds()) {
            final ShipHullSpec hullSpec = ShipHullSpecStore.get(hullId);
            if (hullSpec != null && hullSpec.getSpriteSpec() != null) {
                add(paths, hullSpec.getSpriteSpec().getSpriteName(), "h/" + hullId);
            }
        }
        for (String weaponId : WeaponSpecStore.getWeaponSpecIds()) {
            final BaseWeaponSpec spec = WeaponSpecStore.getWeaponSpec(weaponId);
            final String group = "w/" + weaponId;
            add(paths, spec.getTurretSpriteName(), group);
            add(paths, spec.getHardpointSpriteName(), group);
            add(paths, spec.getTurretUnderSpriteName(), group);
            add(paths, spec.getHardpointUnderSpriteName(), group);
            if (spec instanceof ProjectileWeaponSpec projectile) {
                add(paths, projectile.getTurretGlowSpriteName(), group);
                add(paths, projectile.getHardpointGlowSpriteName(), group);
                add(paths, projectile.getTurretGunSpriteName(), group);
                add(paths, projectile.getHardpointGunSpriteName(), group);
                addAnimationFrames(paths, projectile.getTurretSpriteName(), projectile.getNumFrames(), group);
                addAnimationFrames(paths, projectile.getHardpointSpriteName(), projectile.getNumFrames(), group);
            } else if (spec instanceof BeamWeaponSpec beam) {
                add(paths, beam.getTurretGlowSpriteName(), group);
                add(paths, beam.getHardpointGlowSpriteName(), group);
                addAnimationFrames(paths, beam.getTurretSpriteName(), beam.getNumFrames(), group);
                addAnimationFrames(paths, beam.getHardpointSpriteName(), beam.getNumFrames(), group);
            }
        }
        // 导弹船体与辉光（武器子变体）
        for (String projectileId : WeaponSpecStore.getProjectileSpecIds()) {
            if (WeaponSpecStore.getProjectileSpec(projectileId) instanceof MissileSpec missile) {
                final String group = "m/" + projectileId;
                if (missile.getHullSpec() != null && missile.getHullSpec().getSpriteSpec() != null) {
                    add(paths, missile.getHullSpec().getSpriteSpec().getSpriteName(), group);
                }
                add(paths, missile.getGlowSpriteName(), group);
            }
        }
        // GraphicLib（org.dark.shaders）的材质贴图（material/normal/surface，来源
        // data/lights/*_texture_data.csv）刻意不入图集：其渲染路径不是 Sprite 绘制——
        // LightShader 经 SpriteAPI.getTextureId() 直接绑定贴图到多纹理单元
        // （glActiveTexture + glBindTexture）并用 shader 全域 UV 采样（法线/高光数据，
        // 无 Sprite 的 UV 重映射补偿）。入图集后 getTextureId 返回图集页 id，全域
        // 采样得到整页贴图排列 → 光照计算出现图集格子状串染（曾实证于「攻势XIV」
        // 护盾/引擎高亮区域）。排除后这些贴图走原纹理上传与绑定，语义与原版一致。
        //
        // settings.json graphics 段引用的贴图整体不入图集：该段是模组的通用贴图
        // 注册表，消费方多经 settings API / 自建渲染管线以 0..1 裸 UV 全图采样。
        // 实证：BoxUtil 的 graphics/textures/BUtil_NONE.png 占位贴图被 ASTD 等模组
        // 大量 .wpn/.proj 引用为隐形武器贴图而混入本节收集口径，入图集后 BoxUtil
        // 每个 MaterialData 的默认漫反射槽都绑定到图集页 → 拖尾/光束渲染成图集页
        // 平铺。排除后这些路径走独立纹理上传，与原版语义一致。
        final Set<String> settingsGraphics = collectSettingsGraphicsPaths();
        final int beforeExclude = paths.size();
        paths.keySet().removeAll(settingsGraphics);
        if (beforeExclude != paths.size()) {
            LOGGER.info("[SSOptimizer] Ship/weapon texture atlas: excluded "
                    + (beforeExclude - paths.size()) + " settings.json graphics sprite(s)");
        }
        return paths;
    }

    /**
     * 枚举 settings.json graphics 段引用的全部贴图路径。经游戏自身
     * {@link LoadingUtils#readJSON} 跨 vanilla 与启用模组资源根合并读取，
     * 与游戏运行期的合并语义一致（含模组覆盖）。
     * 读取/解析失败记错误日志并返回空集（不排除任何路径，图集行为与排除前一致）。
     */
    private static Set<String> collectSettingsGraphicsPaths() {
        final JSONObject settings;
        try {
            settings = LoadingUtils.readJSON("data/config/settings.json");
        } catch (IOException | JSONException | RuntimeException e) {
            LOGGER.error("[SSOptimizer] Ship/weapon texture atlas: cannot read settings.json"
                    + " for graphics exclusion, no paths excluded", e);
            return Collections.emptySet();
        }
        return extractGraphicsSpritePaths(settings);
    }

    /**
     * 从 settings.json 根对象提取 graphics 段的全部贴图路径。
     * 段结构为 {@code graphics → 类别 → { 键: 路径 } 或 { 键: [路径...] }}
     * （数组形态对应 {@code StarfarerSettings.getSpriteKeysFromArray}）。
     */
    static Set<String> extractGraphicsSpritePaths(final JSONObject settings) {
        final Set<String> paths = new HashSet<>();
        final JSONObject graphics = settings.optJSONObject("graphics");
        if (graphics == null) {
            return paths;
        }
        final Iterator<String> categories = graphics.keys();
        while (categories.hasNext()) {
            if (graphics.opt(categories.next()) instanceof JSONObject category) {
                final Iterator<String> keys = category.keys();
                while (keys.hasNext()) {
                    final Object value = category.opt(keys.next());
                    if (value instanceof String path) {
                        paths.add(path);
                    } else if (value instanceof JSONArray array) {
                        for (int i = 0; i < array.length(); i++) {
                            final String path = array.optString(i, "");
                            if (!path.isEmpty()) {
                                paths.add(path);
                            }
                        }
                    }
                }
            }
        }
        return paths;
    }

    private static void add(final Map<String, String> paths, final String path, final String group) {
        if (path != null && !path.isEmpty()) {
            paths.putIfAbsent(path, group);
        }
    }

    /**
     * 武器动画帧：基图名为 {@code ...00.png}，后续帧为 {@code ...01.png} 起的两位序号。
     */
    private static void addAnimationFrames(final Map<String, String> paths, final String baseSprite,
                                           final int numFrames, final String group) {
        if (baseSprite == null || !baseSprite.endsWith("00.png")) {
            return;
        }
        final String base = baseSprite.substring(0, baseSprite.length() - "00.png".length());
        for (int frame = 1; frame < numFrames; frame++) {
            paths.putIfAbsent(String.format("%s%02d.png", base, frame), group);
        }
    }

    /**
     * 并行解码全部贴图。解码失败的单张贴图记警告并跳过（不入图集，回退原始渲染）。
     * <p>
     * 线程模型（Wave 3 起）：解码任务跑在 {@link VtWorkers} 虚拟线程上，以 Semaphore
     * 闸门保留原固定池 {@code min(8, cores)} 的并发上限——每张在途解码持有一份
     * 解码中/已解码的 {@link BufferedImage}（大图单张可达数十 MB），闸门约束的是
     * 在途堆内存与 PNG 解码 CPU 占用，而非平台线程数。
     */
    private static Map<String, BufferedImage> decodeAll(final Set<String> paths) {
        final Map<String, BufferedImage> images = new ConcurrentHashMap<>();
        final Semaphore gate = new Semaphore(Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors())));
        final List<Future<?>> futures = new ArrayList<>(paths.size());
        for (String path : paths) {
            futures.add(VtWorkers.submit(() -> {
                try {
                    gate.acquire();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("[SSOptimizer] Atlas decode interrupted", e);
                }
                try {
                    final BufferedImage image = decode(path);
                    if (image != null) {
                        images.put(path, image);
                    }
                } finally {
                    gate.release();
                }
            }));
        }
        // awaitAll 逐个 get 等待全部完成；decode 内部已兜底单图失败（记警告跳过），
        // 走到这里的失败即中断等未预期异常，原样以 RuntimeException 传播
        VtWorkers.awaitAll(futures.toArray(new Future[0]));
        return images;
    }

    private static BufferedImage decode(final String path) {
        try (InputStream stream = ResourceLoader.getInstance().openStream(path)) {
            final BufferedImage image = ImageIO.read(stream);
            if (image == null) {
                LOGGER.warn("[SSOptimizer] Atlas: undecodable image skipped: " + path);
            }
            return image;
        } catch (IOException e) {
            LOGGER.warn("[SSOptimizer] Atlas: image not readable, skipped: " + path + " (" + e.getMessage() + ")");
            return null;
        } catch (RuntimeException e) {
            // ResourceLoader.openStream 对缺失资源抛 RuntimeException（如模组 CSV 引用了不存在的贴图），
            // 与解码失败同级处理：记警告并跳过该路径
            LOGGER.warn("[SSOptimizer] Atlas: image resource missing, skipped: " + path + " (" + e.getMessage() + ")");
            return null;
        }
    }

    /**
     * 把一页的贴图合成图集图像（含 16px 边缘复制 padding）并上传为 GL 纹理。
     * 调用方必须持有 OpenGL 上下文。
     * <p>
     * 图集页是 gameTex 显存账目中「受管贴图真实驻留」的主要构成（入图集后单张贴图
     * 不再上传）；上传后按 RGBA8+mipmap 链入账。图集页无删除路径（无 glDeleteTextures、
     * 无上下文重建处理），账本<b>只计分配峰值</b>。
     *
     * @param pageSize 图集页边长（像素）
     * @return 图集页 GL 纹理 id
     */
    private static int composeAndUpload(final AtlasPacker.Page page, final Map<String, BufferedImage> images,
                                        final int pageSize, final java.nio.file.Path dumpDir) {
        final BufferedImage atlasImage = new BufferedImage(pageSize, pageSize, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = atlasImage.createGraphics();
        try {
            for (AtlasPacker.Placement placement : page.placements()) {
                drawWithPadding(graphics, images.get(placement.path()), placement);
            }
        } finally {
            graphics.dispose();
        }
        dumpPage(atlasImage, dumpDir, page.index());

        final TexturePixelConversionResult converted = TexturePixelConverter.convert(atlasImage);
        final java.nio.IntBuffer idBuffer = BufferUtils.createIntBuffer(1);
        GL11.glGenTextures(idBuffer);
        final int textureId = idBuffer.get(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_GENERATE_MIPMAP, GL11.GL_TRUE);
        TextureUploadHelper.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                pageSize, pageSize, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, converted.buffer());
        // 图集页入 gameTex 显存账目（RGBA8 + GL_GENERATE_MIPMAP 完整链；无删除路径只计峰值）
        GlLedgerHooks.noteGameTexBytes(textureId,
                GlMemoryLedger.withMipmaps((long) pageSize * pageSize * 4L));
        return textureId;
    }

    /**
     * 导出单页图集 PNG（仅当设置了 dumpdir 时调用）。导出失败记错误日志，
     * 不影响图集构建主流程。
     */
    private static void dumpPage(final BufferedImage atlasImage, final java.nio.file.Path dumpDir,
                                 final int pageIndex) {
        if (dumpDir == null) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(dumpDir);
            final java.nio.file.Path file = dumpDir.resolve(String.format("atlas-page-%02d.png", pageIndex));
            ImageIO.write(atlasImage, "PNG", file.toFile());
        } catch (IOException e) {
            LOGGER.error("[SSOptimizer] Ship/weapon texture atlas: failed to dump page " + pageIndex
                    + " to " + dumpDir, e);
        }
    }

    /**
     * 把单张贴图绘制到图集内容原点，并向四边复制边缘像素填充 padding（防 mipmap 渗色）。
     */
    private static void drawWithPadding(final Graphics2D graphics, final BufferedImage image,
                                        final AtlasPacker.Placement placement) {
        final int cx = placement.x();
        final int cy = placement.y();
        final int w = placement.width();
        final int h = placement.height();
        // 内容
        graphics.drawImage(image, cx, cy, cx + w, cy + h, 0, 0, w, h, null);
        // 左右边缘列
        graphics.drawImage(image, cx - PADDING, cy, cx, cy + h, 0, 0, 1, h, null);
        graphics.drawImage(image, cx + w, cy, cx + w + PADDING, cy + h, w - 1, 0, w, h, null);
        // 上下边缘行
        graphics.drawImage(image, cx, cy - PADDING, cx + w, cy, 0, 0, w, 1, null);
        graphics.drawImage(image, cx, cy + h, cx + w, cy + h + PADDING, 0, h - 1, w, h, null);
        // 四角
        graphics.drawImage(image, cx - PADDING, cy - PADDING, cx, cy, 0, 0, 1, 1, null);
        graphics.drawImage(image, cx + w, cy - PADDING, cx + w + PADDING, cy, w - 1, 0, w, 1, null);
        graphics.drawImage(image, cx - PADDING, cy + h, cx, cy + h + PADDING, 0, h - 1, 1, h, null);
        graphics.drawImage(image, cx + w, cy + h, cx + w + PADDING, cy + h + PADDING, w - 1, h - 1, w, h, null);
    }
}
