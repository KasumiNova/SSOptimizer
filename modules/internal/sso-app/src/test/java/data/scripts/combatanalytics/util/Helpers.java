package data.scripts.combatanalytics.util;

import github.kasuminova.ssoptimizer.modopt.dcr.TestProbe;

public final class Helpers {

    private Helpers() {
    }

    public static void printErrorMessage(final String message) {
        TestProbe.recordError();
    }
}
