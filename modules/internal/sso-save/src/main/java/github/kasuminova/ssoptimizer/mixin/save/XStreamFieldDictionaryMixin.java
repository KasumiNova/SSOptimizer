package github.kasuminova.ssoptimizer.mixin.save;

import com.thoughtworks.xstream.converters.reflection.FieldKey;
import com.thoughtworks.xstream.converters.reflection.MissingFieldException;
import github.kasuminova.ssoptimizer.common.save.XStreamFieldDictionaryLookupCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * XStream 字段字典查询加速 Mixin。
 * <p>
 * 注入目标：{@code com.thoughtworks.xstream.converters.reflection.FieldDictionary}<br>
 * 注入动机：热点分析显示 `fieldOrNull/field` 会在存读档过程中高频查询同一批字段；
 * 原实现虽然缓存了类级字典，但每次调用仍会重新进入字典映射并为限定声明类的查询创建新的
 * {@link FieldKey}。<br>
 * 注入效果：为字段查询增加实例级结果缓存（包含未命中结果），降低重复字段解析的常数开销。
 */
@Mixin(targets = "com.thoughtworks.xstream.converters.reflection.FieldDictionary")
public abstract class XStreamFieldDictionaryMixin {
    @Unique
    private final XStreamFieldDictionaryLookupCache ssoptimizer$lookupCache = new XStreamFieldDictionaryLookupCache();

    @Invoker("buildMap")
    protected abstract Map<?, Field> ssoptimizer$invokeBuildMap(Class<?> type, boolean keyedByFieldKey);

    /**
     * 查询字段，若不存在则返回 {@code null}。
     *
     * @param type      发起查找的目标类型
     * @param fieldName 字段名
     * @param definedIn 期望声明类；若为 {@code null} 则执行未限定查找
     * @return 匹配字段；不存在时返回 {@code null}
     * @author GitHub Copilot
     * @reason 为热点字段查询补充结果缓存，避免同一查询在大对象图序列化中反复命中字典 map。
     */
    @Overwrite(remap = false)
    public Field fieldOrNull(final Class<?> type, final String fieldName, final Class<?> definedIn) {
        return ssoptimizer$lookupCache.getOrResolve(type, fieldName, definedIn, () -> {
            final boolean typedLookup = definedIn != null;
            final Map<?, Field> fields = ssoptimizer$invokeBuildMap(type, typedLookup);
            final Object queryKey = typedLookup ? new FieldKey(fieldName, definedIn, -1) : fieldName;
            return fields.get(queryKey);
        });
    }

    /**
     * 查询字段，若不存在则抛出 XStream 原生缺失字段异常。
     *
     * @param type      发起查找的目标类型
     * @param fieldName 字段名
     * @param definedIn 期望声明类；若为 {@code null} 则执行未限定查找
     * @return 匹配字段
     * @author GitHub Copilot
     * @reason 复用缓存化查询结果，同时保持 XStream 原始异常语义不变。
     */
    @Overwrite(remap = false)
    public Field field(final Class<?> type, final String fieldName, final Class<?> definedIn) {
        final Field field = fieldOrNull(type, fieldName, definedIn);
        if (field == null) {
            throw new MissingFieldException(type.getName(), fieldName);
        }
        return field;
    }

    @Inject(method = "flushCache", at = @At("HEAD"), remap = false)
    private void ssoptimizer$clearLookupCache(final CallbackInfo callbackInfo) {
        ssoptimizer$lookupCache.clear();
    }
}