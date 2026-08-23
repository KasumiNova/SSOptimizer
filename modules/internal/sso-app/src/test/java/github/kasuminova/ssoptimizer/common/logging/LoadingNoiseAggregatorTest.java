package github.kasuminova.ssoptimizer.common.logging;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证加载期噪音聚合器（与日志框架无关的核心逻辑）：模式命中计数、flush 汇总输出、
 * 非噪音消息不受影响、多分类输出顺序与空 flush 语义。
 *
 * <p>消息样本全部取自真实 starsector.log 行（仅去掉日志时间戳/线程/logger 前缀）。
 * 不依赖任何日志框架输出通道（reporter 注入收集器），与 log4j 实现无关。</p>
 */
class LoadingNoiseAggregatorTest {

    // ---- 模式清单覆盖：每条样本应命中且计入正确分类 ----

    @Test
    void aggregatesAllNoisePatternSamples() {
        final List<String> reports = new ArrayList<>();
        final LoadingNoiseAggregator aggregator = new LoadingNoiseAggregator(reports::add);

        assertSuppressed(aggregator, "Loading JSON from [data/characters/skills/missile_specialization.skill]");
        assertSuppressed(aggregator, "Class [data.scripts.MirfakParcelServiceModPlugin] already loaded (perhaps from jar file, or due to a reference from another class), skipping compilation.");
        assertSuppressed(aggregator, "Loading rule: defaultOpenDialog");
        assertSuppressed(aggregator, "Loading variant [data/variants/LTHS_CANOE_VARIANT.variant]");
        assertSuppressed(aggregator, "Cleaned buffer for texture graphics/icons/mission_marker.png (using reflection)");
        assertSuppressed(aggregator, "Applying data from weapon_data.csv to [uaf_rillaru_blinker_forward_right] (version 1)");
        assertSuppressed(aggregator, "Applying data from ship_data.csv to [LTHS_Winger]");
        assertSuppressed(aggregator, "Applying data from ship_systems.csv to [AdditionalMissileAssembly]");
        assertSuppressed(aggregator, "Loading weapon [data/weapons/LTHS_Abyss.wpn]");
        assertSuppressed(aggregator, "Loading ship hull [data/hulls/LTHS_AssemblyLine.ship]");
        assertSuppressed(aggregator, "Loaded spec with id [hmdf]");
        assertSuppressed(aggregator, "Loading sound [sounds/weapons/fighters/uaf_missile_fire_2.ogg]");
        assertSuppressed(aggregator, "Loading hullmod [敏捷护盾] (source: [null/data/hullmods/hull_mods.csv])");
        assertSuppressed(aggregator, "Loading CSV data from [DIRECTORY: /mnt/store/Games/Starsector098-linux/./mods/X]");
        assertSuppressed(aggregator, "Loading projectile [data/weapons/proj/LTHS_Abyss_SHOT.proj]");
        assertSuppressed(aggregator, "Loading ship system projectile [data/shipsystems/proj/FM_FighterFlare.proj]");
        assertSuppressed(aggregator, "Loading wing [LTHS_CANOE] from wing_data.csv");
        assertSuppressed(aggregator, "Loading skill [data/characters/skills/hell_march.skill]");
        assertSuppressed(aggregator, "Loading hull skin [data/hulls/skins/A_S-F_exegetes_lg.skin]");
        assertSuppressed(aggregator, "Loading custom campaign entity with id orbital_dockyard");
        assertSuppressed(aggregator, "Loading ability with id transponder");
        assertSuppressed(aggregator, "Loading condition: urbanized_polity");
        assertSuppressed(aggregator, "Loading mission [afistfulofcredits] from [data/missions/afistfulofcredits/descriptor.json] (source: [null/data/missions/mission_list.csv])");
        assertSuppressed(aggregator, "Loading saved variants for mission afistfulofcredits");
        assertSuppressed(aggregator, "Loading terrain with id rat_sea_of_solitude");
        assertSuppressed(aggregator, "Loading submarket with id tachyon_comm");
        assertSuppressed(aggregator, "Loading intel tag with id survey_planet");
        assertSuppressed(aggregator, "Loading ping with id comms");
        assertSuppressed(aggregator, "Loading objective with id sotf_objective_reinf_proxy");
        assertSuppressed(aggregator, "Loading data/campaign/abilities.csv");
        assertSuppressed(aggregator, "Loading contact tag with id marcom");
        assertSuppressed(aggregator, "Loading event with id rep_tracker");
        assertSuppressed(aggregator, "Creating streaming player for music with id [faction_generic_market_0_neutral_var1.ogg]");
        assertSuppressed(aggregator, "Cleaning up music with id [faction_generic_market_0_neutral_var1.ogg]");

        aggregator.flush();
        assertEquals(32, reports.size(), "34 个样本命中 32 个独立分类（3 条 csv data entries 合并为一行）");
        for (String report : reports) {
            assertTrue(report.startsWith("[SSOptimizer] Loaded "), "汇总行格式: " + report);
        }
    }

    @Test
    void flushEmitsLoadedSummaryLinesInFirstSeenOrder() {
        final List<String> reports = new ArrayList<>();
        final LoadingNoiseAggregator aggregator = new LoadingNoiseAggregator(reports::add);

        assertTrue(aggregator.decideSuppress("Loading JSON from [a]"));
        assertTrue(aggregator.decideSuppress("Loading rule: x"));
        assertTrue(aggregator.decideSuppress("Loading JSON from [b]"));
        assertTrue(aggregator.decideSuppress("Cleaned buffer for texture t (using reflection)"));
        assertTrue(aggregator.decideSuppress("Loading JSON from [c]"));

        aggregator.flush();

        assertEquals(List.of(
                "[SSOptimizer] Loaded 3 JSON files",
                "[SSOptimizer] Loaded 1 rules",
                "[SSOptimizer] Loaded 1 texture buffers"
        ), reports, "汇总行按分类首次命中顺序输出，计数精确");
    }

    @Test
    void flushWithEmptyCountsEmitsNothing() {
        final List<String> reports = new ArrayList<>();
        final LoadingNoiseAggregator aggregator = new LoadingNoiseAggregator(reports::add);

        aggregator.flush();
        aggregator.flush();

        assertTrue(reports.isEmpty(), "无计数时 flush 必须零输出（运行期高频日志到达的零开销路径）");
    }

    @Test
    void flushClearsCounts() {
        final List<String> reports = new ArrayList<>();
        final LoadingNoiseAggregator aggregator = new LoadingNoiseAggregator(reports::add);

        aggregator.decideSuppress("Loading JSON from [a]");
        aggregator.flush();
        aggregator.flush();

        assertEquals(List.of("[SSOptimizer] Loaded 1 JSON files"), reports, "第二次 flush 不再重复输出");
    }

    // ---- 非噪音消息不受影响 ----

    @Test
    void nonNoiseMessagesAreNeverSuppressed() {
        final List<String> reports = new ArrayList<>();
        final LoadingNoiseAggregator aggregator = new LoadingNoiseAggregator(reports::add);

        // 加载期诊断信息（有价值，必须保留）
        assertFalse(aggregator.decideSuppress("Ship hull spec [LTHS_AssemblyLine] not found in ship_data.csv"));
        assertFalse(aggregator.decideSuppress("Weapon spec [null] not found!"));
        assertFalse(aggregator.decideSuppress("Ship system [FM_FighterFlare] from ship_systems.csv not found in store"));
        assertFalse(aggregator.decideSuppress("Getting ready to load jar file [data/scripts/ModPlugin.jar]"));
        assertFalse(aggregator.decideSuppress("Compiling script [data/scripts/ModPlugin.java]"));
        // 单条启动诊断（宽泛 Loading 前缀不得误伤；「Loading sound sets」命中 ^Loading sound
        // 属同类加载噪音，聚合进 sounds 分类，不在此断言）
        assertFalse(aggregator.decideSuppress("Loading jar files"));
        assertFalse(aggregator.decideSuppress("Loading whitelisted factions"));
        assertFalse(aggregator.decideSuppress("Loading starsector update info"));
        assertFalse(aggregator.decideSuppress("Loading rules.csv"));
        // 已有信息量的汇总行（不得二次聚合）
        assertFalse(aggregator.decideSuppress("Loaded 7 ship names for group JOKE"));
        // SSOptimizer 自身与第三方 mod 日志
        assertFalse(aggregator.decideSuppress("[SSOptimizer] CoreMod loaded — Engine + AI + loading repair phase active"));
        assertFalse(aggregator.decideSuppress("  Range bonus: 12.3%"));
        // null / 空消息
        assertFalse(aggregator.decideSuppress(null));
        assertFalse(aggregator.decideSuppress(""));

        aggregator.flush();
        assertTrue(reports.isEmpty(), "非噪音消息不得产生任何计数或汇总行");
    }

    @Test
    void noiseMessagesDoNotMatchPartialSiblings() {
        final LoadingNoiseAggregator aggregator = new LoadingNoiseAggregator(reports -> {
        });
        // 「Loading mission 」带尾空格，不吞掉单条的「Loading missions」启动行
        assertFalse(aggregator.decideSuppress("Loading missions"));
        // 「Class ... already loaded」必须含 already loaded 段，纯「Class [x]」编译提示不吞
        assertFalse(aggregator.decideSuppress("Class [com.example.Script] compiling from source"));
        // 「Loading rule: 」不吞规则文件单条
        assertFalse(aggregator.decideSuppress("Loading rules.csv"));
    }

    private static void assertSuppressed(LoadingNoiseAggregator aggregator, String message) {
        assertTrue(aggregator.decideSuppress(message), "应命中噪音模式: " + message);
    }
}