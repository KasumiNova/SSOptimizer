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

@Mixin(targets = "com.thoughtworks.xstream.core.util.SerializationMembers")
public abstract class XStreamSerializationMembersMixin {
    @Unique
    private static final MethodType SSOBJECT_OBJECT = MethodType.methodType(Object.class, Object.class);

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
                // getRRMethod 已完成 setAccessible，unreflect 仅适配既有方法。
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
