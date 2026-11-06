package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.io.path.Path;

import java.util.Arrays;

public final class XStreamPathTrackerHelper {
    private XStreamPathTrackerHelper() {
    }

    public static String[] createFormattedPathStack(final int capacity) {
        return new String[Math.max(1, capacity)];
    }

    public static String[] resizeFormattedPathStack(final String[] original, final int newCapacity) {
        return Arrays.copyOf(original, Math.max(1, newCapacity));
    }

    public static String formatElement(final String elementName, final int index) {
        if (index <= 1) {
            return elementName;
        }
        return elementName + '[' + index + ']';
    }

    public static Path buildPath(final String[] formattedPathStack, final int depth) {
        final String[] chunks = new String[depth + 1];
        chunks[0] = "";
        if (depth > 0) {
            System.arraycopy(formattedPathStack, 0, chunks, 1, depth);
        }
        return new Path(chunks);
    }
}