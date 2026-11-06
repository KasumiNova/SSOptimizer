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

    public record Region(int textureId, int atlasSize, int x, int y) {
    }

    private ShipWeaponAtlas() {
    }

    public static Region lookup(final String texturePath) {
        if (texturePath == null || REGIONS.isEmpty()) {
            return null;
        }
        return REGIONS.get(texturePath);
    }

    // 必须在持有 GL 上下文的主线程调用，重复调用直接返回。
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

    private static java.nio.file.Path resolveDumpDir() {
        final String configured = System.getProperty(DUMP_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return java.nio.file.Paths.get(configured.trim());
    }

    private static int resolvePageSize() {
        final int maxTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        final int pageSize = Math.min(DESIRED_ATLAS_SIZE, maxTextureSize);
        if (pageSize < DESIRED_ATLAS_SIZE) {
            LOGGER.warn("[SSOptimizer] Ship/weapon texture atlas: GL_MAX_TEXTURE_SIZE=" + maxTextureSize
                    + ", page size limited to " + pageSize);
        }
        return pageSize;
    }

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
        for (String projectileId : WeaponSpecStore.getProjectileSpecIds()) {
            if (WeaponSpecStore.getProjectileSpec(projectileId) instanceof MissileSpec missile) {
                final String group = "m/" + projectileId;
                if (missile.getHullSpec() != null && missile.getHullSpec().getSpriteSpec() != null) {
                    add(paths, missile.getHullSpec().getSpriteSpec().getSpriteName(), group);
                }
                add(paths, missile.getGlowSpriteName(), group);
            }
        }
        // GraphicLib 材质与 settings.json graphics 贴图由裸 UV 消费，必须排除，避免图集页被当作单张贴图采样。
        final Set<String> settingsGraphics = collectSettingsGraphicsPaths();
        final int beforeExclude = paths.size();
        paths.keySet().removeAll(settingsGraphics);
        if (beforeExclude != paths.size()) {
            LOGGER.info("[SSOptimizer] Ship/weapon texture atlas: excluded "
                    + (beforeExclude - paths.size()) + " settings.json graphics sprite(s)");
        }
        return paths;
    }

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
            LOGGER.warn("[SSOptimizer] Atlas: image resource missing, skipped: " + path + " (" + e.getMessage() + ")");
            return null;
        }
    }

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

    private static void drawWithPadding(final Graphics2D graphics, final BufferedImage image,
                                        final AtlasPacker.Placement placement) {
        final int cx = placement.x();
        final int cy = placement.y();
        final int w = placement.width();
        final int h = placement.height();
        graphics.drawImage(image, cx, cy, cx + w, cy + h, 0, 0, w, h, null);
        graphics.drawImage(image, cx - PADDING, cy, cx, cy + h, 0, 0, 1, h, null);
        graphics.drawImage(image, cx + w, cy, cx + w + PADDING, cy + h, w - 1, 0, w, h, null);
        graphics.drawImage(image, cx, cy - PADDING, cx + w, cy, 0, 0, w, 1, null);
        graphics.drawImage(image, cx, cy + h, cx + w, cy + h + PADDING, 0, h - 1, w, h, null);
        graphics.drawImage(image, cx - PADDING, cy - PADDING, cx, cy, 0, 0, 1, 1, null);
        graphics.drawImage(image, cx + w, cy - PADDING, cx + w + PADDING, cy, w - 1, 0, w, 1, null);
        graphics.drawImage(image, cx - PADDING, cy + h, cx, cy + h + PADDING, 0, h - 1, 1, h, null);
        graphics.drawImage(image, cx + w, cy + h, cx + w + PADDING, cy + h + PADDING, w - 1, h - 1, w, h, null);
    }
}
