package github.kasuminova.ssoptimizer.mixin.campaign;

import com.fs.graphics.particle.DynamicParticleGroup;
import com.fs.graphics.util.Fader;
import com.fs.profiler.Profiler;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SpawnPointPlugin;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.campaign.BackgroundAndStars;
import com.fs.starfarer.campaign.BaseCampaignEntity;
import com.fs.starfarer.campaign.BaseLocation;
import com.fs.starfarer.campaign.CampaignEngine;
import com.fs.starfarer.campaign.CampaignEntity;
import com.fs.starfarer.campaign.CampaignListener;
import com.fs.starfarer.campaign.CampaignOrbitalStation;
import com.fs.starfarer.campaign.StarSystem;
import com.fs.starfarer.campaign.fleet.Battle;
import com.fs.starfarer.campaign.fleet.CampaignFleet;
import com.fs.starfarer.campaign.rules.Memory;
import com.fs.starfarer.prototype.Utils;
import com.fs.starfarer.util.InputEventList;
import com.fs.util.container.repo.ObjectRepository;
import github.kasuminova.ssoptimizer.common.campaign.CombatPairingGridHelper;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Mixin(targets = GameMixinSignatures.BaseLocation.TARGET_CLASS, remap = false)
public abstract class BaseLocationCombatPairingMixin {
    @Shadow
    private ObjectRepository objects;
    @Shadow
    private BackgroundAndStars background;
    @Shadow
    private DynamicParticleGroup hitParticles;
    @Shadow
    private Color lightColor;
    @Shadow
    private Fader lightHeight;
    @Shadow
    private long lastPlayerVisitTimestamp;
    @Shadow
    private List<SpawnPointPlugin> spawnPoints;
    @Shadow
    private transient Map<String, SectorEntityToken> idToEntity;
    @Shadow
    private Memory memory;

    @Shadow
    public abstract void executeAdds();

    @Shadow
    public abstract void executeRemoves();

    @Shadow
    public abstract boolean isCurrentLocation();

    @Shadow
    public abstract void removeObject(Object entity);

    @Overwrite(remap = false)
    @SuppressWarnings("unchecked")
    public void advance(final float amount, final InputEventList input) {
        final BaseLocation self = (BaseLocation) (Object) this;

        if (!CampaignEngine.getInstance().isPaused()) {
            if (this.memory != null) {
                this.memory.advance(amount);
            }

            this.idToEntity = null;
            final float days = CampaignEngine.getInstance().getClock().convertToDays(amount);
            this.lightHeight.advance(days);
            if (this.background != null && this.isCurrentLocation()) {
                this.background.advance(amount);
            }

            this.hitParticles.advance(amount);
            this.executeAdds();
            this.executeRemoves();
            Profiler.begin("Entity advance");

            for (final CampaignEntity entity
                    : new ArrayList<CampaignEntity>(this.objects.getList(CampaignEntity.class))) {
                if (entity.isExpired()) {
                    this.removeObject(entity);
                } else {
                    entity.setContainingLocation(self);
                    Color entityLightColor = this.lightColor;
                    entity.setContainingLocation(self);
                    final MemoryAPI entityMemory = entity.getMemoryWithoutUpdate();
                    final SectorEntityToken lightSourceOverride =
                            (SectorEntityToken) entityMemory.get("$lightSourceOverride");
                    final Color lightColorOverride = (Color) entityMemory.get("$lightColorOverride");
                    if (lightSourceOverride != null && lightColorOverride != null) {
                        entity.setLightSource(lightSourceOverride, lightColorOverride);
                    } else if (self instanceof StarSystem) {
                        final StarSystem starSystem = (StarSystem) self;
                        Object lightSource = starSystem.getCenter();
                        if (entity.getOrbitFocus() instanceof PlanetAPI) {
                            PlanetAPI planet = (PlanetAPI) entity.getOrbitFocus();
                            if (planet.isStar()) {
                                lightSource = planet;
                            } else if (planet.getOrbitFocus() instanceof PlanetAPI) {
                                planet = (PlanetAPI) planet.getOrbitFocus();
                                if (planet.isStar()) {
                                    lightSource = planet;
                                }
                            }
                        }

                        if (lightSource instanceof PlanetAPI
                                && ((PlanetAPI) lightSource).getLightColorOverrideIfStar() != null) {
                            entityLightColor = ((PlanetAPI) lightSource).getLightColorOverrideIfStar();
                        }

                        entity.setLightSource((SectorEntityToken) lightSource, entityLightColor);
                    } else {
                        entity.setLightSource(null, this.lightColor);
                    }

                    entity.advance(amount);
                    if (entity.getOrbit() != null) {
                        entity.getOrbit().advance(amount);
                    }

                    final Vector2f velocity = entity.getVelocity();
                    final Vector2f location = entity.getLocation();
                    location.x = location.x + velocity.x * amount;
                    location.y = location.y + velocity.y * amount;
                    if (entity.getIndicator() != null) {
                        entity.getIndicator().advance(amount);
                    }
                }
            }

            Profiler.end();
            Profiler.begin("LocationTokens advance");

            for (final BaseLocation.LocationToken token
                    : new ArrayList<BaseLocation.LocationToken>(
                            this.objects.getList(BaseLocation.LocationToken.class))) {
                if (token.getOrbit() != null) {
                    token.getOrbit().advance(amount);
                }

                final Vector2f velocity = token.getVelocity();
                final Vector2f location = token.getLocation();
                location.x = location.x + velocity.x * amount;
                location.y = location.y + velocity.y * amount;
            }

            Profiler.end();
            Profiler.begin("Adds and removes");
            this.executeAdds();
            this.executeRemoves();
            Profiler.end();
            if (amount <= 0.0F) {
                return;
            }

            Profiler.begin("Checking combat initiation");
            final boolean[] encounterStarted = { false };
            final List<CampaignFleet> fleets = this.objects.getList(CampaignFleet.class);
            final int fleetCount = fleets.size();

            if (CombatPairingGridHelper.isEnabled() && fleetCount >= 2) {
                final float[] xs = new float[fleetCount];
                final float[] ys = new float[fleetCount];
                final boolean[] inBattle = new boolean[fleetCount];
                float maxSelectionSize = 0.0F;
                for (int i = 0; i < fleetCount; i++) {
                    final CampaignFleet fleet = fleets.get(i);
                    xs[i] = fleet.getLocation().x;
                    ys[i] = fleet.getLocation().y;
                    inBattle[i] = fleet.getBattle() != null;
                    maxSelectionSize = Math.max(maxSelectionSize, fleet.getSelectionSize());
                }

                CombatPairingGridHelper.forEachCandidatePair(
                        xs, ys, inBattle, 2.0F * maxSelectionSize + 1000.0F,
                        index -> fleets.get(index).getBattle() != null,
                        new CombatPairingGridHelper.PairExaminer() {
                            @Override
                            public boolean canLead(final int index) {
                                return fleets.get(index).canBeEngaged();
                            }

                            @Override
                            public void examine(final int first, final int second) {
                                ssoptimizer$checkCombatPair(fleets.get(first), fleets.get(second),
                                        encounterStarted);
                            }
                        });
            } else {
                for (int i = 0; i < fleetCount; i++) {
                    final CampaignFleet fleetA = fleets.get(i);
                    if (!fleetA.canBeEngaged()) {
                        continue;
                    }
                    for (int j = i + 1; j < fleetCount; j++) {
                        ssoptimizer$checkCombatPair(fleetA, fleets.get(j), encounterStarted);
                    }
                }
            }

            Profiler.end();
            Profiler.begin("Checking station interaction");
            final List<CampaignOrbitalStation> stations = this.objects.getList(CampaignOrbitalStation.class);

            for (int i = 0; i < fleets.size(); i++) {
                final CampaignFleet fleet = fleets.get(i);
                if (fleet.canBeEngaged()) {
                    for (int s = 0; s < stations.size(); s++) {
                        final CampaignOrbitalStation station = stations.get(s);
                        final float dist = Utils.getDistance(fleet.getLocation(), station.getLocation());
                        final float maxDist = fleet.getSelectionSize() + station.getSelectionSize();
                        if (fleet.getInteractionTarget() == station && dist < maxDist) {
                            final CampaignListener listener = CampaignEngine.getInstance().getListener();
                            final CampaignFleet playerFleet = CampaignEngine.getInstance().getPlayerFleet();
                            if (fleet.isPlayerFleet() && listener != null) {
                                listener.startEncounterInvolvingPlayerFleet(playerFleet, station);
                            }
                        }
                    }
                }
            }

            Profiler.end();
            Profiler.begin("Player combat initiation");
            final CampaignFleet playerFleet = CampaignEngine.getInstance().getPlayerFleet();
            if (!encounterStarted[0] && playerFleet.getInteractionTarget() != null
                    && playerFleet.canBeEngaged()) {
                final List<BaseCampaignEntity> entities = this.objects.getList(BaseCampaignEntity.class);

                for (final BaseCampaignEntity entity : new ArrayList<>(entities)) {
                    if (!(entity instanceof CampaignFleet) || ((CampaignFleet) entity).canBeEngaged()) {
                        final float dist = Utils.getDistance(playerFleet.getLocation(), entity.getLocation());
                        final float maxDist = playerFleet.getSelectionSize() + entity.getRadius();
                        if (playerFleet.getInteractionTarget() == entity && dist < maxDist) {
                            final CampaignListener listener = CampaignEngine.getInstance().getListener();
                            if (listener != null && !encounterStarted[0]) {
                                listener.startEncounterInvolvingPlayerFleet(playerFleet, entity);
                                encounterStarted[0] = true;
                            }
                        }
                    }
                }
            }

            Profiler.end();
            Profiler.begin("Spawn points (obsolete)");

            for (final SpawnPointPlugin spawnPoint : this.spawnPoints) {
                spawnPoint.advance(CampaignEngine.getInstance(), self);
            }

            Profiler.end();
        }

        if ((Object) this == CampaignEngine.getInstance().getCurrentLocation()) {
            this.lastPlayerVisitTimestamp = CampaignEngine.getInstance().getClock().getTimestamp();
        }
    }

    private void ssoptimizer$checkCombatPair(final CampaignFleet fleetA, final CampaignFleet fleetB,
                                             final boolean[] encounterFired) {
        if (!fleetB.canBeEngaged()) {
            return;
        }

        final float dist = Utils.getDistance(fleetA.getLocation(), fleetB.getLocation());
        float maxDist = fleetA.getSelectionSize() + fleetB.getSelectionSize();
        if (CampaignEngine.getInstance().isInFastAdvance()) {
            maxDist += 1000.0F;
        }

        if (fleetA.getBattle() != null ^ fleetB.getBattle() != null) {
            BattleAPI battle = fleetA.getBattle();
            CampaignFleet stationCandidate = fleetB;
            if (battle == null) {
                battle = fleetB.getBattle();
                stationCandidate = fleetA;
            }

            final CampaignFleetAPI closestInvolved = battle.getClosestInvolvedFleetTo(stationCandidate);
            if (closestInvolved != null && stationCandidate.isStationMode()) {
                final boolean inSupportRange = Misc.isStationInSupportRange(closestInvolved, stationCandidate);
                if (inSupportRange && battle.canJoin(stationCandidate)) {
                    battle.join(stationCandidate);
                    return;
                }
            }
        }

        if (fleetA.getInteractionTarget() == fleetB || fleetB.getInteractionTarget() == fleetA) {
            final boolean playerInvolved = fleetA.isPlayerFleet() || fleetB.isPlayerFleet();
            // 原版未使用计算结果，但调用本身可能有副作用。
            final float unusedDistToPlayer = Utils.getDistance(
                    Global.getSector().getPlayerFleet().getLocation(), fleetA.getLocation());
            if (fleetA.getBattle() != null ^ fleetB.getBattle() != null) {
                BattleAPI battle = fleetA.getBattle();
                CampaignFleet joinCandidate = fleetB;
                if (battle == null) {
                    battle = fleetB.getBattle();
                    joinCandidate = fleetA;
                }

                final CampaignFleetAPI closestInvolved = battle.getClosestInvolvedFleetTo(joinCandidate);
                if (closestInvolved != null) {
                    if (joinCandidate.isStationMode()) {
                        final boolean inSupportRange = Misc.isStationInSupportRange(closestInvolved, joinCandidate);
                        if (inSupportRange && battle.canJoin(joinCandidate)) {
                            battle.join(joinCandidate);
                            return;
                        }
                    } else {
                        final float distToClosest = Utils.getDistance(
                                closestInvolved.getLocation(), joinCandidate.getLocation());
                        if (distToClosest < closestInvolved.getRadius() + joinCandidate.getRadius()
                                && !playerInvolved
                                && joinCandidate.getAI() != null
                                && joinCandidate.getAI().wantsToJoin(battle, false)
                                && battle.canJoin(joinCandidate)) {
                            battle.join(joinCandidate);
                            return;
                        }
                    }
                }
            }

            if (dist < maxDist && fleetA.getFaction() != fleetB.getFaction()) {
                final CampaignListener listener = CampaignEngine.getInstance().getListener();
                final CampaignFleet playerFleet = CampaignEngine.getInstance().getPlayerFleet();
                boolean hostile = fleetA.getAI() != null && fleetA.getAI().isHostileTo(fleetB);
                hostile |= fleetB.getAI() != null && fleetB.getAI().isHostileTo(fleetA);
                if (playerInvolved && listener != null && !encounterFired[0]) {
                    CampaignFleet otherFleet = fleetA;
                    if (fleetA.isPlayerFleet()) {
                        otherFleet = fleetB;
                    }

                    if (otherFleet.getAI() != null) {
                        otherFleet.getAI().notifyInteractedWith(playerFleet);
                    }

                    listener.startEncounterInvolvingPlayerFleet(playerFleet, otherFleet);
                    encounterFired[0] = true;
                } else if (!playerInvolved
                        && (fleetA.getFaction().isAtBest(fleetB.getFaction(), RepLevel.HOSTILE) || hostile)
                        && (!fleetA.isStationMode() || !fleetB.isStationMode())) {
                    if (fleetA.getAI() != null) {
                        fleetA.getAI().notifyInteractedWith(fleetB);
                    }

                    if (fleetB.getAI() != null) {
                        fleetB.getAI().notifyInteractedWith(fleetA);
                    }

                    if (fleetA.getBattle() == null && fleetB.getBattle() == null) {
                        new Battle(fleetA, fleetB);
                    }
                }
            }
        }
    }
}
