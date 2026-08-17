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
import java.util.LinkedHashSet;
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
 * shelf 装箱进若干 2048² 图集页并上传 GPU；{@code SpriteAtlasMixin} 在
 * {@code Sprite.setTexture} 尾部把 UV 四字段重映射进图集区域，
 * {@link LazyTextureManager} 在绑定层把已入图集路径的 bind/getTextureId 重定向到图集
 * 纹理。效果：同页贴图共享同一 GL 纹理 id，SpriteBatch 合批率与 bind 次数显著改善，
 * 且原始贴图不再单独上传（节省显存）。
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
 * 开关：{@code -Dssoptimizer.atlas.shipweapon=false} 关闭（默认开启）。
 */
public final class ShipWeaponAtlas {
    public static final String ENABLED_PROPERTY = "ssoptimizer.atlas.shipweapon";

    private static final Logger LOGGER = Logger.getLogger(ShipWeaponAtlas.class);

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty(ENABLED_PROPERTY, "true"));
    private static final int ATLAS_SIZE = 2048;
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
        final Set<String> paths = collectSpritePaths();
        final Map<String, BufferedImage> images = decodeAll(paths);
        if (images.isEmpty()) {
            LOGGER.warn("[SSOptimizer] Ship/weapon texture atlas: no sprite decoded, atlas disabled");
            return;
        }

        final List<AtlasPacker.Entry> entries = new ArrayList<>(images.size());
        images.forEach((path, image) -> entries.add(
                new AtlasPacker.Entry(path, image.getWidth(), image.getHeight())));
        final AtlasPacker.Result packed = AtlasPacker.pack(entries, ATLAS_SIZE, PADDING);

        int regionCount = 0;
        for (AtlasPacker.Page page : packed.pages()) {
            final int textureId = composeAndUpload(page, images);
            for (AtlasPacker.Placement placement : page.placements()) {
                // 图像空间（左上原点）→ GL 空间（左下原点）：gY = atlasSize - y - height
                REGIONS.put(placement.path(), new Region(
                        textureId, ATLAS_SIZE, placement.x(), ATLAS_SIZE - placement.y() - placement.height()));
                regionCount++;
            }
        }
        images.clear();

        LOGGER.info(String.format(
                "[SSOptimizer] Ship/weapon texture atlas built: %d regions in %d page(s), %d skipped (oversize), %d ms",
                regionCount, packed.pages().size(), packed.skipped().size(),
                (System.nanoTime() - startNanos) / 1_000_000L));
    }

    /**
     * 枚举舰船/武器系列贴图路径（口径与 {@code ResourceLoaderState.queueShipAndWeaponSprites}
     * 一致，去掉光束与弹丸贴图）。
     */
    private static Set<String> collectSpritePaths() {
        final Set<String> paths = new LinkedHashSet<>();
        for (String hullId : ShipHullSpecStore.getIds()) {
            final ShipHullSpec hullSpec = ShipHullSpecStore.get(hullId);
            if (hullSpec != null && hullSpec.getSpriteSpec() != null) {
                add(paths, hullSpec.getSpriteSpec().getSpriteName());
            }
        }
        for (String weaponId : WeaponSpecStore.getWeaponSpecIds()) {
            final BaseWeaponSpec spec = WeaponSpecStore.getWeaponSpec(weaponId);
            add(paths, spec.getTurretSpriteName());
            add(paths, spec.getHardpointSpriteName());
            add(paths, spec.getTurretUnderSpriteName());
            add(paths, spec.getHardpointUnderSpriteName());
            if (spec instanceof ProjectileWeaponSpec projectile) {
                add(paths, projectile.getTurretGlowSpriteName());
                add(paths, projectile.getHardpointGlowSpriteName());
                add(paths, projectile.getTurretGunSpriteName());
                add(paths, projectile.getHardpointGunSpriteName());
                addAnimationFrames(paths, projectile.getTurretSpriteName(), projectile.getNumFrames());
                addAnimationFrames(paths, projectile.getHardpointSpriteName(), projectile.getNumFrames());
            } else if (spec instanceof BeamWeaponSpec beam) {
                add(paths, beam.getTurretGlowSpriteName());
                add(paths, beam.getHardpointGlowSpriteName());
                addAnimationFrames(paths, beam.getTurretSpriteName(), beam.getNumFrames());
                addAnimationFrames(paths, beam.getHardpointSpriteName(), beam.getNumFrames());
            }
        }
        // 导弹船体与辉光（武器子变体）
        for (String projectileId : WeaponSpecStore.getProjectileSpecIds()) {
            if (WeaponSpecStore.getProjectileSpec(projectileId) instanceof MissileSpec missile) {
                if (missile.getHullSpec() != null && missile.getHullSpec().getSpriteSpec() != null) {
                    add(paths, missile.getHullSpec().getSpriteSpec().getSpriteName());
                }
                add(paths, missile.getGlowSpriteName());
            }
        }
        return paths;
    }

    private static void add(final Set<String> paths, final String path) {
        if (path != null && !path.isEmpty()) {
            paths.add(path);
        }
    }

    /**
     * 武器动画帧：基图名为 {@code ...00.png}，后续帧为 {@code ...01.png} 起的两位序号。
     */
    private static void addAnimationFrames(final Set<String> paths, final String baseSprite, final int numFrames) {
        if (baseSprite == null || !baseSprite.endsWith("00.png")) {
            return;
        }
        final String base = baseSprite.substring(0, baseSprite.length() - "00.png".length());
        for (int frame = 1; frame < numFrames; frame++) {
            paths.add(String.format("%s%02d.png", base, frame));
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
        }
    }

    /**
     * 把一页的贴图合成图集图像（含 16px 边缘复制 padding）并上传为 GL 纹理。
     * 调用方必须持有 OpenGL 上下文。
     *
     * @return 图集页 GL 纹理 id
     */
    private static int composeAndUpload(final AtlasPacker.Page page, final Map<String, BufferedImage> images) {
        final BufferedImage atlasImage = new BufferedImage(ATLAS_SIZE, ATLAS_SIZE, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = atlasImage.createGraphics();
        try {
            for (AtlasPacker.Placement placement : page.placements()) {
                drawWithPadding(graphics, images.get(placement.path()), placement);
            }
        } finally {
            graphics.dispose();
        }

        final TexturePixelConversionResult converted = TexturePixelConverter.convert(atlasImage);
        final java.nio.IntBuffer idBuffer = BufferUtils.createIntBuffer(1);
        GL11.glGenTextures(idBuffer);
        final int textureId = idBuffer.get(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_GENERATE_MIPMAP, GL11.GL_TRUE);
        TextureUploadHelper.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                ATLAS_SIZE, ATLAS_SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, converted.buffer());
        return textureId;
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
