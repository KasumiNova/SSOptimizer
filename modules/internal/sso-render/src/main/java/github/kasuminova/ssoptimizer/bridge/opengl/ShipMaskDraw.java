package github.kasuminova.ssoptimizer.bridge.opengl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLUtessellatorCallbackAdapter;

import java.util.function.Consumer;

public final class ShipMaskDraw extends GLUtessellatorCallbackAdapter {
    private final VertexSink sink;

    private ShipMaskDraw(VertexSink sink) {
        this.sink = sink;
    }

    public static void submit(Consumer<ShipMaskDraw> draw) {
        GlDispatch.submit(VertexBatchCommand.owned(sink -> draw.accept(new ShipMaskDraw(sink))));
    }

    public static void submitTriangles(float[] xy, float r, float g, float b) {
        GlDispatch.submit(VertexBatchCommand.owned(sink -> triangles(sink, xy, r, g, b)));
    }

    public void triangles(float[] xy, float r, float g, float b) {
        triangles(sink, xy, r, g, b);
    }

    private static void triangles(VertexSink sink, float[] xy, float r, float g, float b) {
        sink.color3f(r, g, b);
        sink.begin(GL11.GL_TRIANGLES);
        try {
            for (int i = 0; i < xy.length; i += 2) {
                sink.vertex2f(xy[i], xy[i + 1]);
            }
        } finally {
            sink.end();
        }
    }

    @Override
    public void begin(int type) {
        sink.begin(type);
    }

    @Override
    public void end() {
        sink.end();
    }

    @Override
    public void vertex(Object vertexData) {
        double[] coords = (double[]) vertexData;
        sink.vertex3d(coords[0], coords[1], coords[2]);
        sink.color3d(coords[3], coords[4], coords[5]);
    }
}
