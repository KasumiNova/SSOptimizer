package github.kasuminova.ssoptimizer.common.save;

import java.util.regex.Pattern;

public final class LambdaTypes {
    private static final Pattern NAME = Pattern.compile(".*\\$\\$Lambda(?:\\$[0-9]+|)/.*");

    private LambdaTypes() {
    }

    public static boolean isLambda(final Class<?> type) {
        if (type == null || !type.isSynthetic()) {
            return false;
        }
        final String simpleName = type.getSimpleName();
        return matchesName(simpleName, type.getName());
    }

    static boolean matchesName(final String simpleName, final String binaryName) {
        return NAME.matcher(simpleName.isEmpty() ? binaryName : simpleName).matches();
    }
}
