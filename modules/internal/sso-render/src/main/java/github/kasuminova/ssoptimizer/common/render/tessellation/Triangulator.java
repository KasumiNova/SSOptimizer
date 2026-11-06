package github.kasuminova.ssoptimizer.common.render.tessellation;

public interface Triangulator {

    float[] triangulate(float[] xy, int vertexCount);
}
