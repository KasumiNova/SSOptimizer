package github.kasuminova.ssoptimizer.modopt.dcr;

public final class TestProbe {

    public static int saveCount;
    public static String lastSavedXml;

    public static boolean failSaves;
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
