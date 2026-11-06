package github.kasuminova.ssoptimizer.mixin.loading;

import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.campaign.rules.Rules;
import com.fs.starfarer.loading.FighterWingSpreadsheetLoader;
import com.fs.starfarer.loading.HullVariantSpecStore;
import com.fs.starfarer.loading.LoadingUtils;
import com.fs.starfarer.loading.PersonNameStore;
import com.fs.starfarer.loading.ResourceLoaderState;
import com.fs.starfarer.loading.ShipHullSpecLoader;
import com.fs.starfarer.loading.ShipHullSpecStore;
import com.fs.starfarer.loading.ShipHullSpreadsheetLoader;
import com.fs.starfarer.loading.SpecStore;
import com.fs.starfarer.loading.StarSystemLocations;
import com.fs.starfarer.loading.WeaponSpecLoader;
import com.fs.starfarer.loading.WeaponSpreadsheetLoader;
import com.fs.starfarer.loading.scripts.ScriptStore;
import com.fs.starfarer.loading.specs.HullVariantSpec;
import com.fs.starfarer.loading.specs.ShipEngineSlot;
import com.fs.starfarer.loading.specs.ShipHullSpec;
import com.fs.starfarer.loading.specs.SimulationFleetData;
import com.fs.starfarer.settings.StarfarerSettings;
import github.kasuminova.ssoptimizer.common.concurrent.VtWorkers;
import github.kasuminova.ssoptimizer.common.loading.SpecLoadScheduler;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Mixin(targets = GameClassNames.SPEC_STORE_DOTTED)
public abstract class SpecStoreMixin {
    @Unique
    private static final Logger ssoptimizer$logger = Logger.getLogger(SpecStore.class);

    @Unique
    private static final ConcurrentHashMap<Class<?>, Map<String, Object>> ssoptimizer$specMaps = new ConcurrentHashMap<>();

    @Shadow(remap = false)
    private static void loadEvents(ResourceLoaderState state) throws IOException, JSONException {
        throw new UnsupportedOperationException("shadow");
    }

    @Shadow(remap = false)
    private static void loadAbilities(ResourceLoaderState state) throws IOException, JSONException {
        throw new UnsupportedOperationException("shadow");
    }

    @Shadow(remap = false)
    private static void loadTerrain(ResourceLoaderState state) throws IOException, JSONException {
        throw new UnsupportedOperationException("shadow");
    }

    @Shadow(remap = false)
    private static void loadSectorConfig(ResourceLoaderState state) throws IOException, JSONException {
        throw new UnsupportedOperationException("shadow");
    }

    @Shadow(remap = false)
    private static void loadDescriptions() throws IOException, JSONException {
        throw new UnsupportedOperationException("shadow");
    }

    @Shadow(remap = false)
    private static void loadMissions(ResourceLoaderState state) throws IOException, JSONException {
        throw new UnsupportedOperationException("shadow");
    }

    @Shadow(remap = false)
    private static void loadSoundSets(ResourceLoaderState state) throws IOException, JSONException {
        throw new UnsupportedOperationException("shadow");
    }

    @Overwrite(remap = false)
    public static Map<String, Object> getSpecMap(Class<?> cls) {
        return ssoptimizer$specMaps.computeIfAbsent(cls, key -> new LinkedHashMap<>());
    }

    @Overwrite(remap = false)
    public static void loadStarmap(ResourceLoaderState state) throws IOException, JSONException {
        if (!SpecLoadScheduler.isEnabled()) {
            ssoptimizer$loadStarmapVanilla(state);
            return;
        }

        ssoptimizer$registerPluginScripts();

        SpecLoadScheduler.newDag()
                .task("styles", () -> SpecStore.loadEngineAndHullStyles(state))
                .task("missions", () -> loadMissions(state))
                .task("hullmods", () -> SpecStore.loadHullMods(state))
                .task("events", () -> loadEvents(state))
                .task("abilities", () -> loadAbilities(state))
                .task("terrain", () -> loadTerrain(state))
                .task("pings", () -> SpecStore.loadPings(state))
                .task("intelTags", () -> SpecStore.loadIntelTags(state))
                .task("planetData", () -> SpecStore.loadPlanetData(state))
                .task("soundSets", () -> loadSoundSets(state))
                .task("personalities", SpecStore::loadPersonalities)
                .task("personNames", PersonNameStore::loadNames)
                .task("commodities", () -> SpecStore.loadCommodities(state))
                .task("specialItems", () -> SpecStore.loadSpecialItems(state))
                .task("descriptions", SpecStoreMixin::loadDescriptions)
                .task("sectorConfig", () -> loadSectorConfig(state))
                .task("aptitudes", () -> SpecStore.loadAptitudes(state))
                .task("battleObjectives", () -> SpecStore.loadBattleObjectives(state))
                .task("customEntities", () -> SpecStore.loadCustomCampaignEntities(state))
                .task("marketConditions", () -> SpecStore.loadMarketConditions(state))
                .task("submarkets", () -> SpecStore.loadSubmarkets(state))
                .task("industries", () -> SpecStore.loadIndustries(state))
                .task("personMissions", () -> SpecStore.loadPersonMissions(state))
                .task("barEvents", () -> SpecStore.loadBarEvents(state))
                .task("starmapLocations", () -> StarSystemLocations.load("data/campaign/starmap.json"))
                .task("rules", () -> Rules.loadRules(state))
                .task("projectiles", WeaponSpecLoader::loadProjectiles, "styles")
                .task("hulls", ShipHullSpecLoader::loadHullData, "styles")
                .task("weapons", WeaponSpecLoader::loadWeapons, "projectiles")
                .task("weaponData", WeaponSpreadsheetLoader::load, "weapons")
                .task("shipData", ShipHullSpreadsheetLoader::load, "hulls", "weaponData")
                .task("skins", ShipHullSpecLoader::loadHullSkins, "shipData")
                .task("wings", FighterWingSpreadsheetLoader::load, "skins")
                .task("shipSystems", () -> SpecStore.loadShipSystems(state), "wings")
                .task("simulationVariants", SpecStoreMixin::ssoptimizer$createSimulationVariants, "shipSystems", "weaponData")
                .task("variants", SpecStoreMixin::loadVariants, "simulationVariants")
                .task("skills", () -> SpecStore.loadSkills(state), "aptitudes", "hullmods")
                .task("factions", () -> SpecStore.loadFactions(state),
                        "shipSystems", "weaponData", "hullmods", "variants", "commodities")
                .task("campaignData", SpecStore::loadCampaignData, "variants")
                .task("titleVariants", SpecStore::loadTitleScreenVariants, "variants")
                .task("procgen", () -> SpecStore.loadProcgenData(state),
                        "weaponData", "wings", "commodities", "specialItems", "customEntities", "marketConditions")
                .join();

        ssoptimizer$dumpEngineStyleDiagnostics();
    }

    @Unique
    private static void ssoptimizer$dumpEngineStyleDiagnostics() {
        if (!Boolean.parseBoolean(System.getProperty("ssoptimizer.debug.enginestyle", "false"))) {
            return;
        }
        final Map<String, Object> styles = SpecStore.getSpecMap(ShipEngineSlot.EngineStyle.class);
        ssoptimizer$logger.info("[SSOptimizer] engine style specs: " + styles.size());
        for (Map.Entry<String, Object> entry : styles.entrySet()) {
            final ShipEngineSlot.EngineStyle style = (ShipEngineSlot.EngineStyle) entry.getValue();
            ssoptimizer$logger.info("[SSOptimizer]   style " + entry.getKey()
                    + " glowSprite=" + style.getGlowSprite());
        }

        int totalSlots = 0;
        int customSlots = 0;
        int customWithGlow = 0;
        int customEmptyGlow = 0;
        final Map<String, Integer> glowPathUsage = new LinkedHashMap<>();
        for (String hullId : ShipHullSpecStore.getIds()) {
            final ShipHullSpec hull = ShipHullSpecStore.getOrNull(hullId);
            if (hull == null) {
                continue;
            }
            for (ShipEngineSlot slot : hull.getEngineSlots()) {
                totalSlots++;
                if (slot.getStyleType() != ShipEngineSlot.EngineStyleType.CUSTOM) {
                    continue;
                }
                customSlots++;
                final String glow = slot.toEngineSlot().getGlowTexture();
                if (glow == null || glow.isEmpty()) {
                    customEmptyGlow++;
                    ssoptimizer$logger.info("[SSOptimizer]   CUSTOM slot without glow: hull=" + hullId);
                } else {
                    customWithGlow++;
                    glowPathUsage.merge(glow, 1, Integer::sum);
                }
            }
        }
        ssoptimizer$logger.info("[SSOptimizer] engine slots: total=" + totalSlots
                + " custom=" + customSlots + " customWithGlow=" + customWithGlow
                + " customEmptyGlow=" + customEmptyGlow);
        for (Map.Entry<String, Integer> entry : glowPathUsage.entrySet()) {
            ssoptimizer$logger.info("[SSOptimizer]   glow path " + entry.getKey() + " x" + entry.getValue());
        }
    }

    @Overwrite(remap = false)
    public static void loadVariants() throws IOException, JSONException {
        if (!SpecLoadScheduler.isEnabled()) {
            ssoptimizer$loadVariantsVanilla();
            return;
        }

        final List<String> files = new ArrayList<>(LoadingUtils.listFilesOfType("data/variants", "variant"));
        for (final String subdir : LoadingUtils.listFiles("data/variants")) {
            files.addAll(LoadingUtils.listFilesOfType(subdir, "variant"));
        }

        ssoptimizer$logger.info("Loading ship & fighter variants");

        final Semaphore gate = new Semaphore(SpecLoadScheduler.parallelism());
        final List<CompletableFuture<HullVariantSpec>> futures = new ArrayList<>(files.size());
        for (final String path : files) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    gate.acquire();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(e);
                }
                try {
                    return ssoptimizer$parseVariant(path);
                } catch (final IOException | JSONException e) {
                    throw new CompletionException(e);
                } finally {
                    gate.release();
                }
            }, VtWorkers::submit));
        }

        for (final CompletableFuture<HullVariantSpec> future : futures) {
            final HullVariantSpec spec;
            try {
                spec = future.join();
            } catch (final CompletionException e) {
                final Throwable cause = e.getCause();
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                if (cause instanceof JSONException jsonException) {
                    throw jsonException;
                }
                throw e;
            }
            if (spec != null) {
                // containsVariant 必须在串行注册阶段判定，避免并行解析让同 id 覆盖文件绕过重复检查。
                if (HullVariantSpecStore.containsVariant(spec.getHullVariantId())) {
                    continue;
                }
                HullVariantSpecStore.registerVariant(spec, false);
            }
        }

        ssoptimizer$fixupStationModules();
        HullVariantSpecStore.loadMissionVariants();
    }

    @Unique
    private static HullVariantSpec ssoptimizer$parseVariant(final String path) throws IOException, JSONException {
        ssoptimizer$logger.info("Loading variant [" + path + "]");
        final JSONObject json = LoadingUtils.readJSON(path);
        if (StarfarerSettings.hasTotalConversionMod()) {
            final String hullId = json.getString("hullId");
            if (!ShipHullSpecStore.has(hullId)) {
                ssoptimizer$logger.info("Skipping variant [" + path + "], assuming it's a core variant not used in total conversion.");
                return null;
            }
        }

        final HullVariantSpec spec = new HullVariantSpec(json);
        spec.setSource(VariantSource.STOCK);
        spec.setSourcePath(path);
        return spec;
    }

    @Unique
    private static void ssoptimizer$registerPluginScripts() throws JSONException {
        final JSONObject plugins = StarfarerSettings.getSettingsJSON().getJSONObject("plugins");
        final JSONArray names = plugins.names();
        for (int i = 0; i < names.length(); i++) {
            final String className = plugins.getString(names.getString(i));
            ScriptStore.getScriptClassNames().add(className);
            ScriptStore.registerScriptClass(className);
        }
    }

    @Unique
    private static void ssoptimizer$createSimulationVariants() {
        if (!StarfarerSettings.hasTotalConversionMod()) {
            SimulationFleetData.createAllVariants();
        }
    }

    @Unique
    private static void ssoptimizer$fixupStationModules() throws JSONException {
        for (final String variantId : HullVariantSpecStore.getAllVariantIds()) {
            final HullVariantSpec variant = SpecStore.getSpec(HullVariantSpec.class, variantId);
            if (variant.isEmptyHullVariant() || variant.hasTag("skip_for_default_hull_modules")) {
                continue;
            }
            final HullVariantSpec hullVariant = SpecStore.getSpec(HullVariantSpec.class, variant.getHullSpec().getHullId() + "_Hull");
            if (hullVariant == null || !hullVariant.getStationModules().isEmpty() || variant.getStationModules().isEmpty()) {
                continue;
            }
            for (final String slotId : variant.getStationModules().keySet()) {
                final String moduleId = variant.getStationModules().get(slotId);
                final HullVariantSpec module = SpecStore.getSpec(HullVariantSpec.class, moduleId);
                final String baseHullVariantId = SpecStore.getBaseHullId(module.getHullSpec()) + "_Hull";
                final HullVariantSpec baseHullVariant = SpecStore.getSpec(HullVariantSpec.class, baseHullVariantId);
                if (baseHullVariant != null) {
                    baseHullVariant.setVariantDisplayName("标准");
                    hullVariant.getStationModules().put(slotId, baseHullVariantId);
                }
            }
        }
    }

    @Unique
    private static void ssoptimizer$loadStarmapVanilla(final ResourceLoaderState state) throws IOException, JSONException {
        ssoptimizer$registerPluginScripts();

        SpecStore.loadEngineAndHullStyles(state);
        loadMissions(state);
        SpecStore.loadHullMods(state);
        WeaponSpecLoader.loadProjectiles();
        WeaponSpecLoader.loadWeapons();
        WeaponSpreadsheetLoader.load();
        ShipHullSpecLoader.loadHullData();
        ShipHullSpreadsheetLoader.load();
        ShipHullSpecLoader.loadHullSkins();
        FighterWingSpreadsheetLoader.load();
        SpecStore.loadShipSystems(state);
        loadEvents(state);
        loadAbilities(state);
        loadTerrain(state);
        SpecStore.loadPings(state);
        SpecStore.loadIntelTags(state);
        ssoptimizer$createSimulationVariants();
        ssoptimizer$loadVariantsVanilla();
        SpecStore.loadPlanetData(state);
        loadSoundSets(state);
        SpecStore.loadPersonalities();
        PersonNameStore.loadNames();
        SpecStore.loadCommodities(state);
        SpecStore.loadSpecialItems(state);
        SpecStore.loadFactions(state);
        loadDescriptions();
        loadSectorConfig(state);
        SpecStore.loadCampaignData();
        SpecStore.loadTitleScreenVariants();
        SpecStore.loadAptitudes(state);
        SpecStore.loadSkills(state);
        SpecStore.loadBattleObjectives(state);
        SpecStore.loadCustomCampaignEntities(state);
        SpecStore.loadMarketConditions(state);
        SpecStore.loadSubmarkets(state);
        SpecStore.loadIndustries(state);
        SpecStore.loadProcgenData(state);
        SpecStore.loadPersonMissions(state);
        SpecStore.loadBarEvents(state);
        StarSystemLocations.load("data/campaign/starmap.json");
        Rules.loadRules(state);
    }

    @Unique
    private static void ssoptimizer$loadVariantsVanilla() throws IOException, JSONException {
        final List<String> files = new ArrayList<>(LoadingUtils.listFilesOfType("data/variants", "variant"));
        for (final String subdir : LoadingUtils.listFiles("data/variants")) {
            files.addAll(LoadingUtils.listFilesOfType(subdir, "variant"));
        }

        ssoptimizer$logger.info("Loading ship & fighter variants");

        for (final String path : files) {
            final HullVariantSpec spec = ssoptimizer$parseVariant(path);
            if (spec != null) {
                HullVariantSpecStore.registerVariant(spec, false);
            }
        }

        ssoptimizer$fixupStationModules();
        HullVariantSpecStore.loadMissionVariants();
    }
}
