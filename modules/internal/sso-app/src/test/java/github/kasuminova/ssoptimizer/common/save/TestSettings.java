package github.kasuminova.ssoptimizer.common.save;

import com.fs.starfarer.api.SettingsAPI;
import org.json.JSONObject;

final class TestSettings implements SettingsAPI {
    private final JSONObject settingsJson;

    TestSettings(final JSONObject settingsJson) {
        this.settingsJson = settingsJson;
    }

    @Override
    public boolean getBoolean(final String key) {
        return settingsJson.optBoolean(key, false);
    }

    @Override
    public int getInt(final String key) {
        return settingsJson.optInt(key, 0);
    }

    @Override
    public JSONObject getSettingsJSON() {
        return settingsJson;
    }

    @Override
    public int getBattleSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isShowingCodex() {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.characters.PersonAPI createPerson() {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.campaign.CargoStackAPI createCargoStack(
            final com.fs.starfarer.api.campaign.CargoAPI.CargoItemType itemType,
            final Object data,
            final com.fs.starfarer.api.campaign.CargoAPI cargo) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.ui.LabelAPI createLabel(final String text, final String font) {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getBonusXP(final String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getFloat(final String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean optBoolean(final String key, final boolean defaultValue) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ClassLoader getScriptClassLoader() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isCampaignSensorsOn() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isDevMode() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setDevMode(final boolean devMode) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.awt.Color getColor(final String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getInstanceOfScript(final String className) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getString(final String category, final String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.graphics.SpriteAPI getSprite(final String filename) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.graphics.SpriteAPI getSprite(final String category, final String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.graphics.SpriteAPI getSprite(final com.fs.starfarer.api.SpriteId id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getSpriteName(final String category, final String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.io.InputStream openStream(final String filename) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String loadText(final String filename) {
        throw new UnsupportedOperationException();
    }

    @Override
    public JSONObject loadJSON(final String filename) {
        throw new UnsupportedOperationException();
    }

    @Override
    public org.json.JSONArray loadCSV(final String filename) {
        throw new UnsupportedOperationException();
    }

    @Override
    public org.json.JSONArray getMergedSpreadsheetDataForMod(
            final String filename, final String modId, final String sheetName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public JSONObject getMergedJSONForMod(final String filename, final String modId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getScreenWidth() {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getScreenHeight() {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getScreenWidthPixels() {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getScreenHeightPixels() {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.loading.Description getDescription(
            final String id, final com.fs.starfarer.api.loading.Description.Type type) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.combat.CombatReadinessPlugin getCRPlugin() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getCodeFor(final String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.loading.WeaponSpecAPI getWeaponSpec(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void loadTexture(final String filename) {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getTargetingRadius(final org.lwjgl.util.vector.Vector2f location,
                                    final com.fs.starfarer.api.combat.CombatEntityAPI target,
                                    final boolean considerShield) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.combat.ShipVariantAPI getVariant(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getPlugin(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<String> getSortedSkillIds() {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.characters.SkillSpecAPI getSkillSpec(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getString(final String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.loading.AbilitySpecAPI getAbilitySpec(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<String> getSortedAbilityIds() {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getBaseTravelSpeed() {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getSpeedPerBurnLevel() {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getUnitsPerLightYear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getMaxShipsInFleet() {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.loading.TerrainSpecAPI getTerrainSpec(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.loading.EventSpecAPI getEventSpec(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.campaign.CustomEntitySpecAPI getCustomEntitySpec(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.GameState getCurrentState() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getMaxSensorRange() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getMaxSensorRangeHyper() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getMaxSensorRange(final com.fs.starfarer.api.campaign.LocationAPI location) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<String> getAllVariantIds() {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<String> getAptitudeIds() {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<String> getSkillIds() {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.plugins.LevelupPlugin getLevelupPlugin() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getVersionString() {
        throw new UnsupportedOperationException();
    }

    @Override
    public JSONObject loadJSON(final String filename, final String modId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public org.json.JSONArray loadCSV(final String filename, final String modId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String loadText(final String filename, final String modId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.ModManagerAPI getModManager() {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getBaseFleetSelectionRadius() {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getFleetSelectionRadiusPerUnitSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getMaxFleetSelectionRadius() {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.loading.RoleEntryAPI> getEntriesForRole(
            final String roleId, final String factionId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addEntryForRole(final String roleId, final String factionId,
                                final String variantId, final float weight) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeEntryForRole(final String roleId, final String factionId, final String variantId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.loading.RoleEntryAPI> getDefaultEntriesForRole(final String roleId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addDefaultEntryForRole(final String roleId, final String variantId, final float weight) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeDefaultEntryForRole(final String roleId, final String variantId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void profilerBegin(final String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void profilerEnd() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void profilerPrintResultsTree() {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.campaign.PlanetSpecAPI> getAllPlanetSpecs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getSpec(final Class specClass, final String id, final boolean returnNull) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putSpec(final Class specClass, final String id, final Object spec) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> java.util.Collection<T> getAllSpecs(final Class<T> specClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getRoman(final int number) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void greekLetterReset() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getNextCoolGreekLetter(final Object owner) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getNextGreekLetter(final Object owner) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.characters.MarketConditionSpecAPI getMarketConditionSpec(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.combat.ShipAIPlugin createDefaultShipAI(
            final com.fs.starfarer.api.combat.ShipAPI ship,
            final com.fs.starfarer.api.combat.ShipAIConfig config) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.loading.HullModSpecAPI getHullModSpec(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.loading.FighterWingSpecAPI getFighterWingSpec(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.loading.HullModSpecAPI> getAllHullModSpecs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.loading.FighterWingSpecAPI> getAllFighterWingSpecs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.loading.WeaponSpecAPI> getAllWeaponSpecs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.loading.WeaponSpecAPI> getActuallyAllWeaponSpecs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSoundEnabled() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isInCampaignState() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isGeneratingNewGame() {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getAngleInDegreesFast(final org.lwjgl.util.vector.Vector2f vector) {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getAngleInDegreesFast(final org.lwjgl.util.vector.Vector2f from,
                                       final org.lwjgl.util.vector.Vector2f to) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.campaign.econ.CommoditySpecAPI getCommoditySpec(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.combat.ShipHullSpecAPI getHullSpec(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.campaign.FactionSpecAPI getFactionSpec(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int computeNumFighterBays(final com.fs.starfarer.api.combat.ShipVariantAPI variant) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isInGame() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getNewPluginInstance(final String className) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getControlStringForAbilitySlot(final int slot) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getControlStringForEnumName(final String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isNewPlayer() {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.loading.IndustrySpecAPI getIndustrySpec(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.campaign.econ.CommoditySpecAPI> getAllCommoditySpecs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.loading.IndustrySpecAPI> getAllIndustrySpecs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.campaign.SpecialItemSpecAPI getSpecialItemSpec(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.campaign.SpecialItemSpecAPI> getAllSpecialItemSpecs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.combat.ShipHullSpecAPI> getAllShipHullSpecs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.graphics.SpriteAPI getSprite(
            final String category, final String key, final boolean allowNull) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.combat.ShipVariantAPI createEmptyVariant(
            final String id, final com.fs.starfarer.api.combat.ShipHullSpecAPI hullSpec) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.util.ListMap<String> getHullIdToVariantListMap() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String readTextFileFromCommon(final String filename) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeTextFileToCommon(final String filename, final String text) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean fileExistsInCommon(final String filename) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteTextFileFromCommon(final String filename) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.awt.Color getBasePlayerColor() {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.awt.Color getDesignTypeColor(final String designType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean doesVariantExist(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addCommodityInfoToTooltip(final com.fs.starfarer.api.ui.TooltipMakerAPI tooltip,
                                          final float pad,
                                          final com.fs.starfarer.api.campaign.econ.CommoditySpecAPI commodity,
                                          final int quantity,
                                          final boolean showPrice,
                                          final boolean showDemand,
                                          final boolean showAvailable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public JSONObject getJSONObject(final String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public org.json.JSONArray getJSONArray(final String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.campaign.FactionAPI createBaseFaction(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.characters.MarketConditionSpecAPI> getAllMarketConditionSpecs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.campaign.econ.SubmarketSpecAPI> getAllSubmarketSpecs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getMinArmorFraction() {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getMaxArmorDamageReduction() {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.combat.ShipSystemSpecAPI getShipSystemSpec(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.combat.ShipSystemSpecAPI> getAllShipSystemSpecs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getScreenScaleMult() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getAASamples() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getMouseX() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getMouseY() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getShippingCapacity(final com.fs.starfarer.api.campaign.econ.MarketAPI market,
                                   final boolean includePlayer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void resetCached() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setFloat(final String key, final Float value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setBoolean(final String key, final Boolean value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.loading.PersonMissionSpec> getAllMissionSpecs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.loading.PersonMissionSpec getMissionSpec(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.loading.BarEventSpec> getAllBarEventSpecs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.loading.BarEventSpec getBarEventSpec(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setAutoTurnMode(final boolean autoTurnMode) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isAutoTurnMode() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isShowDamageFloaties() {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getFloatFromArray(final String key, final int index) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getIntFromArray(final String key, final int index) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void loadTextureConvertBlackToAlpha(final String filename) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getControlDescriptionForEnumName(final String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.combat.ShipAIPlugin pickShipAIPlugin(
            final com.fs.starfarer.api.fleet.FleetMemberAPI member,
            final com.fs.starfarer.api.combat.ShipAPI ship) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unloadTexture(final String filename) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void profilerSetEnabled(final boolean enabled) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void profilerReset() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void profilerRestore() {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.awt.Color getBrightPlayerColor() {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.awt.Color getDarkPlayerColor() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void forceMipmapsFor(final String filename, final boolean force) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getGameVersion() {
        throw new UnsupportedOperationException();
    }

    @Override
    public float computeStringWidth(final String text, final String font) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.ui.TextFieldAPI createTextField(final String text, final String font) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.ui.ButtonAPI createCheckbox(
            final String text, final com.fs.starfarer.api.ui.ButtonAPI.UICheckboxSize size) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.ui.ButtonAPI createCheckbox(
            final String text,
            final String font,
            final java.awt.Color color,
            final com.fs.starfarer.api.ui.ButtonAPI.UICheckboxSize size) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.ui.CustomPanelAPI createCustom(
            final float width,
            final float height,
            final com.fs.starfarer.api.campaign.CustomUIPanelPlugin plugin) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getMissionScore(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.loading.WeaponSlotAPI createWeaponSlot(
            final String id,
            final com.fs.starfarer.api.combat.WeaponAPI.WeaponType type,
            final com.fs.starfarer.api.combat.WeaponAPI.WeaponSize size,
            final String spriteName,
            final String textureName,
            final org.lwjgl.util.vector.Vector2f location,
            final float angle,
            final float arc) {
        throw new UnsupportedOperationException();
    }

    @Override
    public org.json.JSONArray getMergedSpreadsheetData(final String filename, final String sheetName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public JSONObject getMergedJSON(final String filename) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setEasySensorBonus(final float bonus) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEnableShipExplosionWhiteout() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setEnableShipExplosionWhiteout(final boolean enabled) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.loading.WeaponSpecAPI> getSystemWeaponSpecs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<String> getSpriteKeys(final String category) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<String> getSpriteKeys(final String category, final String prefix) {
        throw new UnsupportedOperationException();
    }

    @Override
    public JSONObject loadJSON(final String filename, final boolean allowComments) {
        throw new UnsupportedOperationException();
    }

    @Override
    public org.json.JSONArray loadCSV(final String filename, final boolean allowComments) {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getSensorRangeMaxHyper() {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getSensorRangeMax() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void loadFont(final String filename) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isStrafeKeyAToggle() {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.campaign.FactionSpecAPI> getAllFactionSpecs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.characters.PersonalityAPI getPersonaltySpec(final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeJSONToCommon(final String filename, final JSONObject json, final boolean formatted) {
        throw new UnsupportedOperationException();
    }

    @Override
    public JSONObject readJSONFromCommon(final String filename, final boolean allowComments) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<String> getSimOpponents() {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<String> getSimOpponentsDev() {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.Set<String> getIndustrySupply(final String industryId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.Set<String> getIndustryDemand(final String industryId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void showCodex(final String entryId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void showCodex(final com.fs.starfarer.api.impl.codex.CodexEntryPlugin entry) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void showCodex(final java.util.List<com.fs.starfarer.api.impl.codex.CodexEntryPlugin> entries) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void showCodex(final com.fs.starfarer.api.fleet.FleetMemberAPI member) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.fleet.FleetMemberAPI createFleetMember(
            final com.fs.starfarer.api.fleet.FleetMemberType type, final String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.fleet.FleetMemberAPI createFleetMember(
            final com.fs.starfarer.api.fleet.FleetMemberType type,
            final com.fs.starfarer.api.combat.ShipVariantAPI variant) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.List<com.fs.starfarer.api.loading.TerrainSpecAPI> getAllTerrainSpecs() {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.fs.starfarer.api.campaign.SectorEntityToken createLocationToken(final float x, final float y) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasDesignTypeColor(final String designType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getFriendlyFireDanger(final com.fs.starfarer.api.combat.ShipAPI ship,
                                       final com.fs.starfarer.api.combat.CombatEntityAPI target,
                                       final org.lwjgl.util.vector.Vector2f aimPoint,
                                       final org.lwjgl.util.vector.Vector2f fireDirection,
                                       final float range,
                                       final float projectileSpeed,
                                       final float collisionRadius) {
        throw new UnsupportedOperationException();
    }

    @Override
    public float getSafeMovementDir(final com.fs.starfarer.api.combat.ShipAPI ship) {
        throw new UnsupportedOperationException();
    }
}
