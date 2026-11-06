package github.kasuminova.ssoptimizer.mixin.save;

import github.kasuminova.ssoptimizer.common.save.XStreamFieldAccessHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.lang.reflect.Field;

@Mixin(targets = "com.thoughtworks.xstream.core.util.Fields")
public abstract class XStreamFieldsMixin {
    @Overwrite(remap = false)
    public static Object read(final Field field, final Object target) {
        return XStreamFieldAccessHelper.read(field, target);
    }

    @Overwrite(remap = false)
    public static void write(final Field field, final Object target, final Object value) {
        XStreamFieldAccessHelper.write(field, target, value);
    }
}