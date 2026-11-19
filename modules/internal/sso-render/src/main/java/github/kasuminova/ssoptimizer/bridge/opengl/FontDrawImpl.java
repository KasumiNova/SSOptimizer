package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.api.font.FontDraw;

import java.util.List;
import java.util.function.Function;

public final class FontDrawImpl implements FontDraw {
    @Override
    public <P> boolean trySubmit(final List<P> passes,
                                 final Function<? super P, ? extends List<? extends GlyphVertices>> glyphs,
                                 final boolean atlas, final int blendSrc, final int blendDst) {
        if (!GlDispatch.canSubmitOwnedDraw()) {
            return false;
        }
        final FontTextDraw draw = FontTextDraw.capture(passes, glyphs, atlas, blendSrc, blendDst);
        if (draw == null) {
            return false;
        }
        GlDispatch.submit(VertexBatchCommand.owned(draw::replay));
        draw.updateRecordingState(BridgeSupport.simulatedState());
        return true;
    }
}
