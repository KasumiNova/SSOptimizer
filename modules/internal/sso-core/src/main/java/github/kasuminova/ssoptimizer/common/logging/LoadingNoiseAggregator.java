package github.kasuminova.ssoptimizer.common.logging;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 加载期噪音日志聚合器（与日志框架无关的核心逻辑）。
 *
 * <p>动机：游戏加载期会由 {@code loading.*}/{@code rules.Rules}/{@code scripts.ScriptStore}/
 * {@code graphics.TextureLoader}/{@code sound.*} 等原版 logger 打出十几万行单条无信息量的
 * INFO（「Loading JSON from ...」「Class [...] already loaded ...」「Cleaned buffer for
 * texture ...」等，实测单次启动约 14.8 万行、占总日志六成）。整 logger 阈值压制（原有的
 * {@code setLevel(WARN)}）会连带吞掉「not found」「Getting ready to load jar file」等有
 * 诊断价值的 INFO；本聚合器改为<b>按消息内容</b>逐条压制刷屏行，并把同类计数聚合成一条
 * 「Loaded N &lt;分类&gt;」风格的汇总 INFO 替代，达到既压噪又保诊断的效果。</p>
 *
 * <p>职责边界：本类只负责「消息是否命中聚合模式」「按分类计数」「flush 时产出汇总行」。
 * 不依赖任何日志框架类型（reporter 由适配层注入），因此可被 log4j2 Filter（生产链路）与
 * log4j 1.x Filter（真实 log4j-1.2.17 端到端验证）共用同一份逻辑。</p>
 *
 * <p>flush 时机（由适配层 decide 驱动）：任意一条<b>不命中聚合模式</b>的日志或任意
 * WARN/ERROR 到达时 flush 全部累计计数——加载期结束后必然有后续日志（如 SSOptimizer 自身
 * 的启动报告），累计计数最迟在此时输出，无需 shutdown hook；运行期高频 WARN 到达时计数
 * 已空，flush 无输出无开销。单条汇总行随真实日志流插入，便于用户检索关键输出。</p>
 *
 * <p>线程安全：游戏加载期多线程（SpecLoad/VariantParse/加载/脚本线程）并发打日志，
 * decide 入口以 synchronized 串行化，计数与 flush 原子可见。</p>
 */
public final class LoadingNoiseAggregator {
    /**
     * 噪音消息模式条目：正则 + 聚合分类标签。
     *
     * @param regex  匹配消息开头的前缀正则（{@code ^} 锚定；完整消息本身以该前缀开头即命中）
     * @param label  聚合统计行使用的复数分类名，flush 时输出为 {@code Loaded N <label>}
     */
    public record NoisePattern(Pattern regex, String label) {
        /**
         * 消息是否命中本模式（前缀匹配，非全串匹配）。
         *
         * @param message 日志事件的消息文本（rendered message）
         * @return true=该消息属于本噪音分类
         */
        boolean matches(String message) {
            return regex.matcher(message).find();
        }
    }

    /**
     * 噪音消息清单（数据驱动，实测频率降序；来自 starsector.log 的 grep 统计归纳）。
     *
     * <p>覆盖：用户指定的必过滤模式（Loading JSON / Loading rule / Class ... already loaded /
     * Cleaned buffer / Loading hullmod）+ 日志统计发现的其他高频加载段（>1000 条）+ 各类
     * spec 加载行（ShipSpec/WeaponSpec/FighterWingSpec/SoundSpec/变体/技能/能力等家族）+
     * sound 声音流创建/清理刷屏。不在清单内的高频段：{@code hud.RadarCompositeCacheImpl}
     * 的 WARN（高级别不聚合，必须保留）、第三方 mod 运行期刷屏（
     * {@code hullmods.No101_CoincidenceRangefinder} 等，属 mod 自身行为，不越权压制）。</p>
     */
    static final List<NoisePattern> NOISE_PATTERNS = List.of(
            // ---- 高频主段（单类 >1000 条，实测频率降序） ----
            noise("^Loading JSON from ", "JSON files"),                        // 56978
            noise("^Class .* already loaded", "classes"),                      // 30004
            noise("^Loading rule: ", "rules"),                                 // 19716
            noise("^Loading variant ", "variants"),                            // 6963
            noise("^Cleaned buffer for texture ", "texture buffers"),          // 4669
            noise("^Applying data from [A-Za-z_]+\\.csv to ", "csv data entries"), // 8236（ship/weapon/ship_systems）
            noise("^Loading weapon ", "weapon specs"),                         // 3932
            noise("^Loading ship hull ", "ship hull specs"),                   // 3326
            noise("^Loaded spec with id ", "specs"),                           // 3322
            noise("^Loading sound ", "sounds"),                                // 2873
            noise("^Loading hullmod ", "hullmods"),                            // 2046
            noise("^Loading CSV data from ", "CSV files"),                     // 1962
            noise("^Loading projectile ", "projectile specs"),                 // 1878
            noise("^Loading ship system ", "ship system specs"),               // 1116
            // ---- 低频 spec 加载家族（单类 <1000，与高频段同属原版加载噪音） ----
            noise("^Loading wing ", "fighter wings"),                          // 591
            noise("^Loading skill ", "skills"),                                // 405
            noise("^Loading hull skin ", "hull skins"),                        // 393
            noise("^Loading custom campaign entity ", "custom campaign entities"), // 371
            noise("^Loading ability with id ", "abilities"),                   // 295
            noise("^Loading condition", "conditions"),                         // 281
            noise("^Loading mission ", "missions"),                            // 108
            noise("^Loading saved variants ", "saved variants"),               // 106
            noise("^Loading terrain", "terrains"),                             // 56
            noise("^Loading submarket", "submarkets"),                         // 50
            noise("^Loading intel tag", "intel tags"),                         // 37
            noise("^Loading ping", "pings"),                                   // 33
            noise("^Loading objective", "objectives"),                         // 26
            noise("^Loading data/", "data files"),                             // 17
            noise("^Loading contact tag", "contact tags"),                     // 10
            noise("^Loading event with ", "events"),                           // 3
            // ---- sound 声音流管理刷屏（原在整 logger 压制名单内，改消息级后由本表接管） ----
            noise("^Creating streaming player for music with id ", "music streams"),      // 346
            noise("^Cleaning up music with id ", "music stream cleanups")                // 344
    );

    /** 汇总行前缀（与 SSOptimizer 自身日志统一标识，便于用户检索）。 */
    private static final String REPORT_PREFIX = "[SSOptimizer] Loaded ";

    /** 分类标签 -> 累计计数（LinkedHashMap 保持首次命中顺序，flush 输出稳定有序）。 */
    private final Map<String, Long> pendingCounts = new LinkedHashMap<>();
    /** 汇总行输出通道：生产为日志框架的 INFO 输出，测试注入收集器断言。 */
    private final Consumer<String> reporter;

    /**
     * @param reporter 汇总行输出通道（接收完整的「Loaded N &lt;label&gt;」文本行）
     */
    public LoadingNoiseAggregator(Consumer<String> reporter) {
        this.reporter = reporter;
    }

    /**
     * 判定一条 INFO 消息是否属于聚合噪音：命中则按分类计数并返回 true（适配层应 DENY）。
     *
     * @param message 日志事件的消息文本；null 视为非噪音
     * @return true=已被聚合压制（调用方应拦截该条日志）
     */
    public synchronized boolean decideSuppress(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        for (NoisePattern pattern : NOISE_PATTERNS) {
            if (pattern.matches(message)) {
                pendingCounts.merge(pattern.label(), 1L, Long::sum);
                return true;
            }
        }
        return false;
    }

    /**
     * 输出全部累计计数为汇总行并清空。
     * <p>必须先快照并清空再输出：汇总行本身会再次经过本过滤器（不匹配任何聚合模式，
     * 触发 flush），若输出期间计数未清空将无限递归（每条汇总行再次输出整组汇总）。</p>
     * <p>计数为空时无输出（运行期高频 WARN/INFO 到达时零开销）。</p>
     */
    public synchronized void flush() {
        if (pendingCounts.isEmpty()) {
            return;
        }
        final Map<String, Long> snapshot = new LinkedHashMap<>(pendingCounts);
        pendingCounts.clear();
        for (Map.Entry<String, Long> entry : snapshot.entrySet()) {
            reporter.accept(REPORT_PREFIX + entry.getValue() + " " + entry.getKey());
        }
    }

    /** 构造模式条目（统一转义入口，避免每行重复 new Pattern）。 */
    private static NoisePattern noise(String regex, String label) {
        return new NoisePattern(Pattern.compile(regex), label);
    }
}
