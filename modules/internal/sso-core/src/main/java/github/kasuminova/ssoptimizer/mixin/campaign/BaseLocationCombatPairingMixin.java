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

/**
 * 星域交战检测近距分桶 Mixin（B4，默认开，可回退）。
 * <p>
 * 注入目标：{@code com.fs.starfarer.campaign.BaseLocation#advance(float, InputEventList)}<br>
 * 注入动机：「Checking combat initiation」段对全部可交战舰队两两配对 O(F²)
 * （每对含距离 sqrt、battle XOR、interactionTarget 判定），密集舰队星域下是
 * {@code StarSystem.advance} 热点的重要组成部分；且非当前 location 每 60 帧轮转
 * 也会完整执行该段（{@code CampaignEngine.advance} 轮转 advance），开销随全星域舰队数平方增长。<br>
 * 为什么必须 @Overwrite 而不是锚点注入：配对枚举是方法中段的双层循环，
 * Mixin 无法在「保留循环体」的前提下替换「配对枚举方式」——
 * 循环体不是任何单一 INVOKE 调用点，{@code @Redirect}/{@code @Inject} 都无法跳过中段代码；
 * 项目编译期无 MixinExtras（{@code @WrapWithCondition} 等）可用。
 * 故逐行对照 named 源码覆写整个 {@code advance}，除配对枚举外一字不改。<br>
 * 注入效果：{@code -Dssoptimizer.campaign.combatPairing=true}（默认）时配对枚举走
 * {@link CombatPairingGridHelper} 格网分桶（同格+邻格 ∪ 全部 battle XOR 配对，
 * 被剪配对在原版语义下必然无效果，结果集合与原版完全一致，枚举顺序不变）；
 * {@code =false} 时走原版双层循环，逐字节等价回退。<br>
 * 配对体逻辑（距离上限、battle join 接力、遭遇创建）两行路径共用同一份
 * {@link #ssoptimizer$checkCombatPair} 转抄实现，杜绝双份漂移。<br>
 * 等价性论证与前提假设见 {@link CombatPairingGridHelper} 类注释。
 */
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

    /**
     * @reason 配对枚举分桶化（动机与「为什么必须覆写」见类注释）；
     *         除「Checking combat initiation」段的配对枚举外，逐行对照原版转抄。
     */
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
                // 分桶配对：坐标/战斗状态/selectionSize 上限快照进格网，
                // 配对体经 ssoptimizer$checkCombatPair 共享，判定逻辑一行不改
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
                // 原版回退路径：双层全配对循环，配对体与分桶路径共享同一份转抄实现
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

    /**
     * 「Checking combat initiation」段单配对判定体（原版内层循环体逐行转抄）。
     * <p>
     * 原版内层循环头的 {@code var38.canBeEngaged()} 惰性判定保留在方法头；
     * 原版三处 {@code continue}（join 成功后跳过本对剩余判定）对应本方法的提前 return；
     * 外层 {@code var22}（玩家遭遇已触发标志）以 {@code encounterFired[0]} 传递。
     *
     * @param fleetA         外层舰队（对应原版 var29）
     * @param fleetB         内层舰队（对应原版 var38）
     * @param encounterFired 玩家遭遇已触发标志（对应原版 var22）
     */
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
            // 原版死代码（计算结果从未使用），逐行保留以维持调用副作用时序
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
