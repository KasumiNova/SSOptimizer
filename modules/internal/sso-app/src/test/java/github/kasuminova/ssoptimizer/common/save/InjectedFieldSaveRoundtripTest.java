package github.kasuminova.ssoptimizer.common.save;

import com.fs.starfarer.campaign.save.IntArrayConverter;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.ConversionException;
import com.thoughtworks.xstream.io.xml.StaxDriver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mixin 注入字段（{@code ssoptimizer$*}）与战役存档 XStream 序列化的契约验证。
 * <p>
 * 背景：{@code CampaignShipEngineGlowMixin.ssoptimizer$glowGeometryCache} 曾未标
 * transient，被 XStream 写入存档；读档时 {@link IntArrayConverter} 解析空 int 数组
 * 节点抛出 {@code NumberFormatException: For input string: ""}（实机读档失败）。
 * 修复口径：注入字段一律 transient——写出侧不进入存档，读入侧旧（已污染）存档中
 * 残留的 {@code ssoptimizer$*} 节点按「transient 字段节点跳过」语义静默忽略。
 * <p>
 * 本测试用真实 XStream（游戏同款 1.4.21_miko）+ 游戏同款 {@link IntArrayConverter}
 * 配置做最小复现：夹具类复刻注入字段的形态（同名、transient/非 transient 两版），
 * 验证写出排除、污染 XML 读入、旧存档读入三个方向。
 */
class InjectedFieldSaveRoundtripTest {

    /** 带 bug 版本的形态：非 transient 注入对象字段（会被 XStream 序列化进存档）。 */
    public static class BuggyHolder {
        public int    normalValue;
        public Object ssoptimizer$glowGeometryCache;
    }

    /** 修复后的形态：同名注入字段标 transient。 */
    public static class FixedHolder {
        public           int    normalValue;
        public transient Object ssoptimizer$glowGeometryCache;
    }

    /** 模拟 GlowGeometryCache：持有空 int[]（marshal 产出空 {@code <i-a>} 节点）。 */
    public static class FakeGeometryCache {
        public int[] counts = new int[0];
    }

    private static XStream gameLikeXStream(final Class<?> holderClass) {
        // 对齐 CampaignGameManager.getXStream 的相关配置：i-a alias + IntArrayConverter
        final XStream xstream = new XStream(new StaxDriver());
        xstream.alias("holder", holderClass);
        xstream.alias("i-a", int[].class);
        xstream.registerConverter(new IntArrayConverter(), 10000);
        return xstream;
    }

    /**
     * 复现污染源头：非 transient 注入字段会被写出，空 int[] 经 IntArrayConverter
     * 产出空 {@code <counts>} 节点（用户坏档的实际内容；字段名经 XStream
     * XmlFriendlyMapper 转义，{@code $} 写作 {@code _-}）。
     */
    @Test
    void buggyVersionWritesInjectedFieldWithEmptyIntArrayNode() {
        final XStream xstream = gameLikeXStream(BuggyHolder.class);
        final BuggyHolder holder = new BuggyHolder();
        holder.normalValue = 7;
        holder.ssoptimizer$glowGeometryCache = new FakeGeometryCache();

        final String xml = xstream.toXML(holder);

        assertTrue(xml.contains("glowGeometryCache"),
                "非 transient 注入字段必须出现在存档 XML 中（污染复现前提）: " + xml);
    }

    /**
     * 复现用户读档失败：带 bug 版本读自己写出的存档，空 {@code <i-a>} 触发
     * {@code NumberFormatException: For input string: ""}。
     */
    @Test
    void buggyVersionFailsToReadItsOwnPollutedSave() {
        final XStream xstream = gameLikeXStream(BuggyHolder.class);
        final BuggyHolder holder = new BuggyHolder();
        holder.ssoptimizer$glowGeometryCache = new FakeGeometryCache();
        final String pollutedXml = xstream.toXML(holder);

        final ConversionException exception = assertThrows(ConversionException.class,
                () -> xstream.fromXML(pollutedXml));
        Throwable cause = exception;
        boolean numberFormatInChain = false;
        while (cause != null) {
            if (cause instanceof NumberFormatException) {
                numberFormatInChain = true;
                break;
            }
            cause = cause.getCause();
        }
        assertTrue(numberFormatInChain,
                "错误链必须包含 NumberFormatException（空 i-a 节点解析失败）: " + exception);
    }

    /** 修复后写出侧：transient 注入字段不进入存档 XML，普通字段照常。 */
    @Test
    void fixedVersionDoesNotSerializeInjectedField() {
        final XStream xstream = gameLikeXStream(FixedHolder.class);
        final FixedHolder holder = new FixedHolder();
        holder.normalValue = 7;
        holder.ssoptimizer$glowGeometryCache = new FakeGeometryCache();

        final String xml = xstream.toXML(holder);

        assertFalse(xml.contains("glowGeometryCache"),
                "transient 注入字段不得写入存档: " + xml);
        assertTrue(xml.contains("<normalValue>7</normalValue>"), "普通字段照常序列化");
    }

    /**
     * 已污染存档的读取兼容：修复后代码读「含 {@code ssoptimizer$glowGeometryCache}
     * 节点（内嵌空 {@code <i-a>}）」的旧 XML——transient 字段节点被静默跳过，
     * 不触发 IntArrayConverter，普通字段正常读回。
     */
    @Test
    void pollutedSaveFromBuggyVersionReadsCleanlyAfterFix() {
        final BuggyHolder buggy = new BuggyHolder();
        buggy.normalValue = 7;
        buggy.ssoptimizer$glowGeometryCache = new FakeGeometryCache();
        final String pollutedXml = gameLikeXStream(BuggyHolder.class).toXML(buggy);

        final FixedHolder restored =
                (FixedHolder) gameLikeXStream(FixedHolder.class).fromXML(pollutedXml);

        assertEquals(7, restored.normalValue);
        assertNull(restored.ssoptimizer$glowGeometryCache,
                "transient 注入字段读档后保持 JVM 默认值（运行期自然重建）");
    }

    /** 反向兼容：修复前写出的旧存档（无注入字段节点）读修复后代码无问题。 */
    @Test
    void legacySaveWithoutInjectedNodeReadsCleanly() {
        final String legacyXml = "<holder><normalValue>3</normalValue></holder>";

        final FixedHolder restored =
                (FixedHolder) gameLikeXStream(FixedHolder.class).fromXML(legacyXml);

        assertEquals(3, restored.normalValue);
        assertNull(restored.ssoptimizer$glowGeometryCache);
    }
}
