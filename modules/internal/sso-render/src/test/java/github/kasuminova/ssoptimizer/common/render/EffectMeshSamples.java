package github.kasuminova.ssoptimizer.common.render;

import java.util.ArrayList;
import java.util.List;

public final class EffectMeshSamples implements EffectVertices {
    public record Vertex(float x, float y, float u, float v, int rgba) {}
    public record Primitive(int mode, List<Vertex> vertices) {}
    public final List<Primitive> primitives = new ArrayList<>();
    private List<Vertex> current;
    private int rgba;
    private float u = Float.NaN, v = Float.NaN;

    public void begin(int mode) {
        if (current != null) throw new AssertionError("重复开始图元");
        current = new ArrayList<>();
        primitives.add(new Primitive(mode, current));
    }

    public void end() {
        if (current == null) throw new AssertionError("缺少起始图元");
        current = null;
    }

    public void color(byte r, byte g, byte b, byte a) {
        rgba = (r & 255) << 24 | (g & 255) << 16 | (b & 255) << 8 | a & 255;
    }

    public void uv(float u, float v) { this.u = u; this.v = v; }
    public void vertex(float x, float y) { current.add(new Vertex(x, y, u, v, rgba)); }
}
