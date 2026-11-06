package github.kasuminova.ssoptimizer;

import org.apache.log4j.Logger;

public final class App {
    private static final Logger LOGGER = Logger.getLogger(App.class);

    private App() {
    }

    static void main(String[] args) {
        LOGGER.info("[SSOptimizer] SSOptimizer bootstrap running on Java " + Runtime.version());
    }
}
