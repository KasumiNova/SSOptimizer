package github.kasuminova.ssoptimizer.standalone.remap;

import java.io.IOException;

public sealed class PreparationException extends IOException {
    private PreparationException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public static final class Input extends PreparationException {
        public Input(final String message) { super(message, null); }
        public Input(final String message, final Throwable cause) { super(message, cause); }
    }

    public static final class Conflict extends PreparationException {
        public Conflict(final String message) { super(message, null); }
        public Conflict(final String message, final Throwable cause) { super(message, cause); }
    }

    public static final class Cache extends PreparationException {
        public Cache(final String message, final Throwable cause) { super(message, cause); }
    }
}
