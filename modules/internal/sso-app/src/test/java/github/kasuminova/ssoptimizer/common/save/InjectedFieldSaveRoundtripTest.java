package github.kasuminova.ssoptimizer.common.save;

import com.fs.starfarer.campaign.save.IntArrayConverter;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.ConversionException;
import com.thoughtworks.xstream.io.xml.StaxDriver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InjectedFieldSaveRoundtripTest {

    public static class BuggyHolder {
        public int    normalValue;
        public Object ssoptimizer$glowGeometryCache;
    }

    public static class FixedHolder {
        public           int    normalValue;
        public transient Object ssoptimizer$glowGeometryCache;
    }

    public static class FakeGeometryCache {
        public int[] counts = new int[0];
    }

    private static XStream gameLikeXStream(final Class<?> holderClass) {
        final XStream xstream = new XStream(new StaxDriver());
        xstream.alias("holder", holderClass);
        xstream.alias("i-a", int[].class);
        xstream.registerConverter(new IntArrayConverter(), 10000);
        return xstream;
    }

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

    @Test
    void legacySaveWithoutInjectedNodeReadsCleanly() {
        final String legacyXml = "<holder><normalValue>3</normalValue></holder>";

        final FixedHolder restored =
                (FixedHolder) gameLikeXStream(FixedHolder.class).fromXML(legacyXml);

        assertEquals(3, restored.normalValue);
        assertNull(restored.ssoptimizer$glowGeometryCache);
    }
}
