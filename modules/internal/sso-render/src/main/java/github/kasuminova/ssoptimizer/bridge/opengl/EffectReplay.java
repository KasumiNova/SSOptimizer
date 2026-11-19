package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.EffectVertices;

public final class EffectReplay {
    private EffectReplay() {}

    public static void submit(EffectVertices.Mesh mesh) {
        GlDispatch.submit(VertexBatchCommand.owned(sink -> mesh.emit(new Output(sink))));
    }

    private record Output(VertexSink sink) implements EffectVertices {
        public void begin(int mode) { sink.begin(mode); }
        public void end() { sink.end(); }
        public void color(byte r, byte g, byte b, byte a) { sink.color4ub(r, g, b, a); }
        public void uv(float u, float v) { sink.texCoord2f(u, v); }
        public void vertex(float x, float y) { sink.vertex2f(x, y); }
    }
}
