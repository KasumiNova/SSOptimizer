package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.io.path.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XStreamPathTrackerHelperTest {
    @Test
    void formatsIndexedElementsLikeXStream() {
        assertEquals("fleet", XStreamPathTrackerHelper.formatElement("fleet", 1));
        assertEquals("fleet[2]", XStreamPathTrackerHelper.formatElement("fleet", 2));
    }

    @Test
    void buildsPathFromFormattedStack() {
        String[] formatted = XStreamPathTrackerHelper.createFormattedPathStack(4);
        formatted[0] = "campaign";
        formatted[1] = "fleet[2]";

        Path path = XStreamPathTrackerHelper.buildPath(formatted, 2);

        assertEquals("/campaign/fleet[2]", path.toString());
        assertEquals(path, new Path("/campaign/fleet[2]"));
    }
}