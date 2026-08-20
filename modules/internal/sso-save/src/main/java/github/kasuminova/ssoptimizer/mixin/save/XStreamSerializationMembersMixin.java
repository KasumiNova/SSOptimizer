package github.kasuminova.ssoptimizer.mixin.save;

import com.thoughtworks.xstream.converters.ConversionException;
import com.thoughtworks.xstream.converters.ErrorWritingException;
import com.thoughtworks.xstream.converters.reflection.ObjectAccessException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XStream 序列化成员方法调用 MethodHandle 缓存 Mixin。
 * <p>
 * 注入目标：{@code com.thoughtworks.xstream.core.util.SerializationMembers}<br>
 * 注入动机：读档时 {@code callReadResolve} 对每个对象节点调用，存档同理
 * {@code callWriteReplace}；命中方法的调用走 {@code Method.invoke}，
 * 每次调用伴随访问检查与委派开销。<br>
 * 注入效果：按 (类, 方法名) 缓存已适配 {@code (Object)Object} 签名的 MethodHandle，
 * 调用开销接近普通虚方法调用。异常包装语义与原版一致。<br>
 * 反射使用说明：{@code MethodHandles.unreflect} 仅作用于 XStream 原版已完成
 * setAccessible 的方法句柄，属一次性的调用点适配，非运行期反射查找。
 */
@Mixin(targets = "com.thoughtworks.xstream.core.util.SerializationMembers")
public abstract class XStreamSerializationMembersMixin {
    @Unique
    private static final MethodType SSOBJECT_OBJECT = MethodType.methodType(Object.class, Object.class);

    /**
     * 无方法占位句柄，避免缓存 null 值。
     */
    @Unique
    private static final MethodHandle SSNO_HANDLE = MethodHandles.dropArguments(
            MethodHandles.constant(Object.class, null), 0, Object.class);

    @Unique
    private final Map<String, MethodHandle> ssoptimizer$readResolveHandles = new ConcurrentHashMap<>();

    @Unique
    private final Map<String, MethodHandle> ssoptimizer$writeReplaceHandles = new ConcurrentHashMap<>();

    @Shadow
    private Method getRRMethod(final Class<?> type, final String name) {
        throw new AssertionError("Shadow method was not transformed");
    }

    /**
     * 覆写 callReadResolve：MethodHandle 调用。
     *
     * @param result 反序列化完成的对象
     * @return readResolve 返回值或原对象
     * @author KasumiNova
     * @reason 每对象节点的 Method.invoke 委派开销在百万级节点下可测，
     * MethodHandle 缓存后调用开销接近直接调用。
     */
    @Overwrite(remap = false)
    public Object callReadResolve(final Object result) {
        if (result == null) {
            return null;
        }
        final Class<?> resultType = result.getClass();
        final MethodHandle handle = ssoptimizer$handleFor(
                ssoptimizer$readResolveHandles, resultType, "readResolve");
        if (handle == null) {
            return result;
        }
        try {
            return handle.invokeExact(result);
        } catch (final Throwable t) {
            final ConversionException ex = new ConversionException("Failed calling method", t);
            ex.add("method", resultType.getName() + ".readResolve()");
            throw ex;
        }
    }

    /**
     * 覆写 callWriteReplace：MethodHandle 调用。
     *
     * @param object 待序列化对象
     * @return writeReplace 返回值或原对象
     * @author KasumiNova
     * @reason 同 callReadResolve，写侧对称优化。
     */
    @Overwrite(remap = false)
    public Object callWriteReplace(final Object object) {
        if (object == null) {
            return null;
        }
        final Class<?> objectType = object.getClass();
        final MethodHandle handle = ssoptimizer$handleFor(
                ssoptimizer$writeReplaceHandles, objectType, "writeReplace");
        if (handle == null) {
            return object;
        }
        try {
            final Object replaced = handle.invokeExact(object);
            if (replaced != null && !object.getClass().equals(replaced.getClass())) {
                return callWriteReplace(replaced);
            }
            return replaced;
        } catch (final ConversionException | ObjectAccessException e) {
            final ErrorWritingException ex = e;
            ex.add("method", objectType.getName() + ".writeReplace()");
            throw ex;
        } catch (final Throwable t) {
            final ConversionException ex = new ConversionException("Failed calling method", t);
            ex.add("method", objectType.getName() + ".writeReplace()");
            throw ex;
        }
    }

    /**
     * 解析并缓存方法句柄；目标类无对应方法时返回 null（以占位句柄缓存该结果）。
     */
    @Unique
    private MethodHandle ssoptimizer$handleFor(final Map<String, MethodHandle> cache,
                                               final Class<?> type, final String name) {
        final MethodHandle cached = cache.get(type.getName() + '.' + name);
        if (cached != null) {
            return cached == SSNO_HANDLE ? null : cached;
        }
        final Method method = getRRMethod(type, name);
        MethodHandle handle = SSNO_HANDLE;
        if (method != null) {
            try {
                // 原版 getRRMethod 命中时已完成 setAccessible，unreflect 跳过访问检查
                handle = MethodHandles.lookup().unreflect(method).asType(SSOBJECT_OBJECT);
            } catch (final IllegalAccessException e) {
                final ObjectAccessException ex = new ObjectAccessException("Cannot access method", e);
                ex.add("method", type.getName() + "." + name + "()");
                throw ex;
            }
        }
        cache.put(type.getName() + '.' + name, handle);
        return handle == SSNO_HANDLE ? null : handle;
    }
}
