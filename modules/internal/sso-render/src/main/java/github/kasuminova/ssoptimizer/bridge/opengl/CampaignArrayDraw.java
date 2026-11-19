package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.campaign.CampaignArrayMesh;
import github.kasuminova.ssoptimizer.common.render.campaign.CampaignMeshBuffers;
import github.kasuminova.ssoptimizer.common.render.queue.DiscardableGlCommand;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

/** 渲染线程独占展开缓冲。调用点已按原顺序设置客户端数组开关与混合状态。 */
public final class CampaignArrayDraw {
    private static final CampaignMeshBuffers SCRATCH = new CampaignMeshBuffers();

    private CampaignArrayDraw() {}

    public static void submit(CampaignArrayMesh mesh) {
        GlDispatch.submit(new Batch(mesh, BridgeSupport.pointerState().retainNormal()));
    }

    private record Batch(CampaignArrayMesh mesh, PointerSnapshot normal) implements DiscardableGlCommand {
        @Override
        public void execute() {
            try {
                SCRATCH.fill(mesh);
                draw(mesh.mode(), mesh.vertexCount(), normal);
            } finally {
                try {
                    GlDispatch.onExternalGlStateChange();
                } finally {
                    discard();
                }
            }
        }

        @Override
        public void discard() {
            if (normal != null) normal.release();
        }
    }

    private static void draw(int mode, int count, PointerSnapshot normal) {
        int previous = BridgeSupport.executedArrayBufferBinding();
        int binding = previous;
        try {
            if (normal != null) {
                int required = normal.data == null ? normal.vboId : 0;
                if (binding != required) {
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, required);
                    binding = required;
                }
                normal.apply();
            }
            if (binding != 0) {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
                binding = 0;
            }
            GL11.glColorPointer(4, true, 0, SCRATCH.colors());
            GL11.glVertexPointer(2, 0, SCRATCH.xy());
            GL11.glTexCoordPointer(2, 0, SCRATCH.uv());
            if (BridgeSupport.isCompilingDisplayList()) {
                GL11.glBegin(mode);
                try {
                    for (int i = 0; i < count; i++) GL11.glArrayElement(i);
                } finally {
                    GL11.glEnd();
                }
            } else {
                GL11.glDrawArrays(mode, 0, count);
            }
        } finally {
            if (binding != previous) GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
        }
    }
}
