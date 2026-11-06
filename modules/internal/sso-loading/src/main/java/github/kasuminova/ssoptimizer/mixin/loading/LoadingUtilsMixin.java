package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.loading.LoadingJsonCommentStripper;
import github.kasuminova.ssoptimizer.common.loading.LoadingTextResourceReader;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.json.JSONException;
import org.json.JSONObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.io.IOException;
import java.io.InputStream;

@Mixin(targets = GameClassNames.LOADING_UTILS_DOTTED)
public abstract class LoadingUtilsMixin {

    @Overwrite(remap = false)
    public static String readText(InputStream inputStream) throws IOException {
        return LoadingTextResourceReader.read(inputStream);
    }

    @Overwrite(remap = false)
    public static JSONObject parseJSONStrippingComments(String sourceName, String text) throws JSONException {
        return LoadingJsonCommentStripper.parse(sourceName, text);
    }
}
