package github.kasuminova.ssoptimizer.modopt.dcr;

/**
 * 父加载器持有的探针：子加载器加载的转换后夹具经 saveValue 写入此处，测试由此读取「存盘次数」与末次内容，
 * 全程无反射（探针为父加载器共享类）。
 */
public final class TestProbe {

    public static int saveCount;
    public static String lastSavedXml;

    /** 置 true 时夹具 saveValue 抛异常，用于验证 flush 的 try/catch 不抛出 onGameLoad。 */
    public static boolean failSaves;
    /** Helpers.printErrorMessage 调用次数（错误路径计数）。 */
    public static int errorCount;

    private TestProbe() {
    }

    public static void reset() {
        saveCount = 0;
        lastSavedXml = null;
        failSaves = false;
        errorCount = 0;
    }

    public static void recordSave(final String xml) {
        saveCount++;
        lastSavedXml = xml;
    }

    public static void recordError() {
        errorCount++;
    }
}
