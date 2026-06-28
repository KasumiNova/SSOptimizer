package data.scripts.combatanalytics.util;

import github.kasuminova.ssoptimizer.modopt.dcr.TestProbe;

/**
 * 测试夹具：模拟 DCR 的 {@code data/scripts/combatanalytics/util/Helpers}，提供合成 flush 的 catch 分支
 * 所调用的 {@code printErrorMessage(String)}。记录到 {@link TestProbe} 以便错误路径断言。
 */
public final class Helpers {

    private Helpers() {
    }

    public static void printErrorMessage(final String message) {
        TestProbe.recordError();
    }
}
