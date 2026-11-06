package github.kasuminova.ssoptimizer.common.loading;

public enum AlphaKind {
    OPAQUE,
    BINARY,
    FULL;

    public static AlphaKind fromOrdinal(final int ordinal) {
        final AlphaKind[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("Unknown AlphaKind ordinal: " + ordinal);
        }
        return values[ordinal];
    }
}
