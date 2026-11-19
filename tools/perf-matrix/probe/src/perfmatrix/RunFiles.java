package perfmatrix;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import java.io.IOException;

final class RunFiles {
    static final String TOKEN = System.getenv("SSO_PERF_RUN");
    private static final StringBuilder EVENTS = new StringBuilder();
    private static final StringBuilder ROSTER = new StringBuilder("snapshot\tside\tindex\tvariant\tdp\n");
    private RunFiles() {}

    static void save(String key, String value) throws IOException {
        Global.getSettings().writeTextFileToCommon("sso_perf_" + TOKEN + "_" + key, value);
    }

    static void event(String name, long nano) throws IOException {
        remember(name, nano);
        save("events", EVENTS.toString());
        Global.getLogger(RunFiles.class).info("[PerfMatrix] " + name);
    }

    static void remember(String name, long nano) {
        EVENTS.append(name).append('\t').append(nano).append('\t').append(System.currentTimeMillis()).append('\n');
    }

    static void metadata() throws IOException {
        StringBuilder meta = new StringBuilder();
        meta.append("game\t").append(Global.getSettings().getVersionString()).append('\n');
        meta.append("jvm\t").append(System.getProperty("java.version")).append('\n');
        meta.append("gl_renderer\t").append(GL11.glGetString(GL11.GL_RENDERER)).append('\n');
        meta.append("gl_vendor\t").append(GL11.glGetString(GL11.GL_VENDOR)).append('\n');
        meta.append("dimensions\t").append(Display.getWidth()).append('x').append(Display.getHeight()).append('\n');
        int order = 0;
        for (ModSpecAPI mod : Global.getSettings().getModManager().getEnabledModsCopy()) {
            meta.append("mod\t").append(order++).append('\t').append(mod.getId()).append('\t').append(mod.getVersion()).append('\n');
        }
        save("metadata", meta.toString());
    }

    static void member(String phase, String side, int index, String variant, float dp) {
        ROSTER.append(phase).append('\t').append(side).append('\t').append(index).append('\t').append(variant).append('\t').append(dp).append('\n');
    }

    static void saveRoster() throws IOException { save("roster", ROSTER.toString()); }
}
