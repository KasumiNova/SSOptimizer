package github.kasuminova.ssoptimizer.common.render.campaign;

public interface CampaignArrayMesh {
    int mode();
    int vertexCount();
    void expand(Vertex out);

    @FunctionalInterface
    interface Vertex {
        void put(float x, float y, float u, float v, int rgba);
    }
}
