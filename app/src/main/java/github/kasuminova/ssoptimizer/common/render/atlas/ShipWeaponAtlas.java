package github.kasuminova.ssoptimizer.common.render.atlas;

import com.fs.starfarer.loading.ShipHullSpecStore;
import com.fs.starfarer.loading.WeaponSpecStore;
import com.fs.starfarer.loading.specs.BaseWeaponSpec;
import com.fs.starfarer.loading.specs.BeamWeaponSpec;
import com.fs.starfarer.loading.specs.MissileSpec;
import com.fs.starfarer.loading.specs.ProjectileWeaponSpec;
import com.fs.starfarer.loading.specs.ShipHullSpec;
import com.fs.util.ResourceLoader;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 舰船/武器贴图动态图集。
 * <p>
 * 加载期（{@code ResourceLoaderState.init} 返回点，主线程持 GL 上下文）把全部舰船
 * 船体图与武器系列贴图（炮塔/硬点/底衬/辉光/枪管/动画帧，以及导弹船体与辉光）
 * shelf 装箱进若干图集页（期望 8192²，按 {@code GL_MAX_TEXTURE_SIZE} 收敛；按船体/武器/弹体 id
 * 亲和分组排布以提高同页命中率）并上传 GPU；{@code SpriteAtlasMixin} 在
 * {@code Sprite.setTexture} 尾部把 UV 四字段重映射进图集区域并缓存图集页纹理 id，
 * {@link LazyTextureManager} 在<b>绑定路径</b>把已入图集路径的 bind 重定向到图集纹理。
 * 效果：同页贴图共享同一 GL 纹理 id，SpriteBatch 合批率与 bind 次数显著改善，
 * 且原始贴图在纯 Sprite 渲染路径下不再单独上传（节省显存）。
 * <p>
 * 双轨制：{@code TextureObject.getTextureId()} 始终返回原始纹理真实 id（不随图集
 * 重定向）——raw id 消费方（GraphicLib 光照 pass、模组自定义 shader 等）以原始
 * UV 空间自行采样，若拿图集 id 会把整页图集当单张贴图绘制（实机已复现串染）。
 * 合批路径由 SpriteAtlasMixin 缓存的图集 id 提交，不经 getTextureId。
 * 同理，GraphicLib 光照贴图（material/normal/surface）只被 raw id 消费，不入图集。
 * <p>
 * 防渗色：每张贴图四边 16px 边缘复制 padding + 图集生成 mipmap。
 * <p>
 * 已知边界：
 * <ul>
 *   <li>与 LazyTextureManager 原始延迟加载模式（{@code starfarer.settings} 级开关）
 *       不兼容——该模式下贴图元数据在 setTexture 时不可用，构建直接跳过；</li>
 *   <li>图集纹理 id 绑定到构建时的 GL 上下文，运行期显示模式重建上下文后需重启
 *       （与原版大纹理行为一致，初版不做上下文热重建）；</li>
 *   <li>光束 core/fringe 与弹丸贴图使用平铺/自定义 UV，不入图集。</li>
 * </ul>
 * 开关：{@code -Dssoptimizer.atlas.shipweapon=false} 关闭（默认开启）；
 * {@code -Dssoptimizer.atlas.shipweapon.dumpdir=<dir>} 导出每页 PNG 与区域坐标表
 * （atlas-regions.tsv：page/x/y/w/h + 路径，图像空间）供检查空间利用率与区域定位。
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
        final StringBuilder regionDump = dumpDir != null ? new StringBuilder() : null;
        for (AtlasPacker.Page page : packed.pages()) {
            final int textureId = composeAndUpload(page, images, pageSize, dumpDir);
            for (AtlasPacker.Placement placement : page.placements()) {
                // 图像空间（左上原点）→ GL 空间（左下原点）：gY = atlasSize - y - height
                REGIONS.put(placement.path(), new Region(
                        textureId, pageSize, placement.x(), pageSize - placement.y() - placement.height()));
                if (regionDump != null) {
                    // 图像空间坐标（与导出的页 PNG 直接对应），便于人工定位区域
                    regionDump.append(page.index()).append('\t')
                            .append(placement.x()).append('\t').append(placement.y()).append('\t')
                            .append(placement.width()).append('\t').append(placement.height()).append('\t')
                            .append(placement.path()).append('\n');
                }
                regionCount++;
                contentArea += (long) placement.width() * placement.height();
                cellArea += (long) (placement.width() + PADDING * 2) * (placement.height() + PADDING * 2);
            }
        }
        images.clear();
        dumpRegionTable(regionDump, dumpDir);

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
     */
    private static Map<String, BufferedImage> decodeAll(final Set<String> paths) {
        final Map<String, BufferedImage> images = new ConcurrentHashMap<>();
        final int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        final ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "SSOptimizer-Atlas-Decode");
            thread.setDaemon(true);
            return thread;
        });
        try {
            final List<Future<?>> futures = new ArrayList<>(paths.size());
            for (String path : paths) {
                futures.add(pool.submit(() -> {
                    final BufferedImage image = decode(path);
                    if (image != null) {
                        images.put(path, image);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("[SSOptimizer] Atlas decode interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("[SSOptimizer] Atlas decode failed", e.getCause());
        } finally {
            pool.shutdown();
        }
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
     * 导出区域坐标表（仅当设置了 dumpdir 时调用）：page/x/y/w/h（图像空间）+ 路径，
     * 与页 PNG 配套，供人工定位某张贴图在图集中的位置。导出失败记错误日志。
     */
    private static void dumpRegionTable(final StringBuilder regionDump, final java.nio.file.Path dumpDir) {
        if (regionDump == null || dumpDir == null) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(dumpDir);
            java.nio.file.Files.writeString(dumpDir.resolve("atlas-regions.tsv"), regionDump.toString());
        } catch (IOException e) {
            LOGGER.error("[SSOptimizer] Ship/weapon texture atlas: failed to dump region table to " + dumpDir, e);
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
