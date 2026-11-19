package github.kasuminova.ssoptimizer.common.render.campaign;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public final class CampaignMeshBuffers implements CampaignArrayMesh.Vertex {
    private ByteBuffer colors;
    private FloatBuffer xy, uv;

    public void fill(CampaignArrayMesh mesh) {
        int vertices = mesh.vertexCount();
        if (colors == null || colors.capacity() < vertices * 4) {
            int capacity = Integer.highestOneBit(Math.max(32, vertices) - 1) << 1;
            colors = BufferUtils.createByteBuffer(capacity * 4);
            xy = BufferUtils.createFloatBuffer(capacity * 2);
            uv = BufferUtils.createFloatBuffer(capacity * 2);
        }
        colors.clear();
        xy.clear();
        uv.clear();
        mesh.expand(this);
        colors.flip();
        xy.flip();
        uv.flip();
    }

    public void put(float x, float y, float u, float v, int rgba) {
        xy.put(x).put(y);
        uv.put(u).put(v);
        colors.put((byte) (rgba >>> 24)).put((byte) (rgba >>> 16)).put((byte) (rgba >>> 8)).put((byte) rgba);
    }

    public FloatBuffer xy() { return xy; }
    public FloatBuffer uv() { return uv; }
    public ByteBuffer colors() { return colors; }

    void drawRecorded(CampaignArrayMesh mesh) {
        fill(mesh);
        GL11.glColorPointer(4, true, 0, colors);
        GL11.glVertexPointer(2, 0, xy);
        GL11.glTexCoordPointer(2, 0, uv);
        GL11.glDrawArrays(mesh.mode(), 0, mesh.vertexCount());
    }
}
