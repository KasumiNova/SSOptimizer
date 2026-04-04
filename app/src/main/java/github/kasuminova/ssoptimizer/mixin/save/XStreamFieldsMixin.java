package github.kasuminova.ssoptimizer.mixin.save;

import github.kasuminova.ssoptimizer.common.save.XStreamFieldAccessHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.lang.reflect.Field;

/**
 * XStream 字段工具类的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.thoughtworks.xstream.core.util.Fields}<br>
 * 注入动机：XStream 在存档保存阶段会高频调用 {@code read(Field, Object)} 读取对象图字段，原实现每次都走
 * 反射 {@link Field#get(Object)}，在大存档和多 Mod 场景下累计开销明显；这里改为委托到带缓存的
 * {@link XStreamFieldAccessHelper}，把字段解析成本摊平。<br>
 * 注入效果：覆盖 XStream 的字段读写入口，优先走缓存化的 {@code VarHandle} 快路径，并保留反射回退。
 */
@Mixin(targets = "com.thoughtworks.xstream.core.util.Fields")
public abstract class XStreamFieldsMixin {
    /**
     * 读取字段值。
     *
     * @param field  要读取的字段
     * @param target 字段所属对象；静态字段时可为 {@code null}
     * @return 字段值
     * @author GitHub Copilot
     * @reason 将 XStream 存档热点里的逐字段反射读取替换为带缓存的快路径，同时保留原语义回退。
     */
    @Overwrite(remap = false)
    public static Object read(final Field field, final Object target) {
        return XStreamFieldAccessHelper.read(field, target);
    }

    /**
     * 向字段写入值。
     *
     * @param field  要写入的字段
     * @param target 字段所属对象；静态字段时可为 {@code null}
     * @param value  新值
     * @author GitHub Copilot
     * @reason 复用同一套字段访问缓存逻辑，减少纯反射 provider 回退时的写入开销。
     */
    @Overwrite(remap = false)
    public static void write(final Field field, final Object target, final Object value) {
        XStreamFieldAccessHelper.write(field, target, value);
    }
}