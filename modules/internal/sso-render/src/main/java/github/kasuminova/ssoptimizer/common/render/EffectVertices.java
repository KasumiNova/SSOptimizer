package github.kasuminova.ssoptimizer.common.render;

import github.kasuminova.ssoptimizer.bridge.opengl.EffectReplay;
import org.lwjgl.opengl.GL11;

public interface EffectVertices {
    EffectVertices RECORDED = new EffectVertices() {
        public void begin(int mode) { GL11.glBegin(mode); }
        public void end() { GL11.glEnd(); }
        public void color(byte r, byte g, byte b, byte a) { GL11.glColor4ub(r, g, b, a); }
        public void uv(float u, float v) { GL11.glTexCoord2f(u, v); }
        public void vertex(float x, float y) { GL11.glVertex2f(x, y); }
    };

    void begin(int mode);
    void end();
    void color(byte r, byte g, byte b, byte a);
    void uv(float u, float v);
    void vertex(float x, float y);

    @FunctionalInterface
    interface Mesh {
        void emit(EffectVertices out);
    }

    static void submit(Mesh snapshot) {
        EffectReplay.submit(snapshot);
    }
}
