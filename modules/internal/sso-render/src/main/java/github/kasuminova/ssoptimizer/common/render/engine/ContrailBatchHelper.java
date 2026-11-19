package github.kasuminova.ssoptimizer.common.render.engine;

import com.fs.graphics.TextureObject;
import com.fs.graphics.util.GLListManager;
import com.fs.starfarer.loading.specs.EngineSlot;
import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import github.kasuminova.ssoptimizer.common.render.spritebatch.SpriteBatch;
import github.kasuminova.ssoptimizer.mixin.accessor.ContrailGroupAccessor;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Map;

public final class ContrailBatchHelper {
    private ContrailBatchHelper() {
    }

    public static boolean renderContrails(Map<?, ?> groups, float alphaScale) {
        boolean queued = RenderThreadMode.isEnabled();
        if (queued ? !GlDispatch.canSubmitOwnedDraw() : GLListManager.buildingList) {
            return false;
        }

        SpriteBatch.getInstance().flushPending();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        boolean blendSet = false;
        EngineSlot.BlendMode activeBlend = null;
        TextureObject boundTexture = null;
        try (ContrailRecorder recorder = new ContrailRecorder(alphaScale,
                queued ? ContrailBatch::enqueue : ContrailBatch::drawDirect)) {
            for (Object value : groups.values()) {
                ContrailGroupAccessor group = (ContrailGroupAccessor) value;
                if (group == null) {
                    continue;
                }
                EngineSlot.BlendMode blend = group.ssoptimizer$getBlendMode();
                boolean changeBlend = !blendSet || activeBlend != blend;
                TextureObject texture = group.ssoptimizer$getTexture();
                List<Object> segments = group.ssoptimizer$getSegments();
                boolean drawable = texture != null && segments != null && segments.size() > 1;
                boolean changeTexture = drawable && texture != boundTexture;
                if (changeBlend) {
                    recorder.flush();
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA,
                            blend == EngineSlot.BlendMode.GLOW ? GL11.GL_ONE : GL11.GL_ONE_MINUS_SRC_ALPHA);
                    blendSet = true;
                    activeBlend = blend;
                }
                if (!drawable) {
                    continue;
                }
                if (changeTexture) {
                    texture.bind();
                    boundTexture = texture;
                }
                recorder.capture(group, segments);
            }
            recorder.finish(queued);
        }
        return true;
    }
}
