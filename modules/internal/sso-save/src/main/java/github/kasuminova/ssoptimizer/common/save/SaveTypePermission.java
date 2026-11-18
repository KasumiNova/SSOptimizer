package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.converters.ConversionException;
import com.thoughtworks.xstream.mapper.Mapper;
import com.thoughtworks.xstream.security.TypePermission;

import java.awt.Color;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Member;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Vector;

public final class SaveTypePermission implements TypePermission {
    private static final Set<Class<?>> VALUES = Set.of(
            Object.class, String.class, Boolean.class, Byte.class, Short.class, Integer.class,
            Long.class, Float.class, Double.class, Character.class, BigInteger.class, BigDecimal.class,
            Date.class, GregorianCalendar.class, Locale.class, UUID.class, Random.class, Color.class,
            Collection.class, List.class, Set.class, SortedSet.class, Map.class, SortedMap.class,
            ArrayList.class, LinkedList.class, HashMap.class, LinkedHashMap.class, TreeMap.class,
            HashSet.class, LinkedHashSet.class, TreeSet.class, Hashtable.class, Vector.class,
            Map.Entry.class, AbstractMap.SimpleEntry.class, AbstractMap.SimpleImmutableEntry.class,
            Mapper.Null.class);

    private final List<TypePermission> restrictions;
    private final Set<Class<?>> lambdaTypes;
    private final boolean hasLambdaBindings;
    private volatile Set<Class<?>> registered;

    public SaveTypePermission(final Collection<Class<?>> registered, final List<TypePermission> restrictions,
                              final RegisteredLambdas lambdas) {
        this.registered = componentTypes(registered);
        this.restrictions = List.copyOf(restrictions);
        this.lambdaTypes = lambdas.runtimeTypes();
        this.hasLambdaBindings = !lambdas.isEmpty();
    }

    void retainRegistrations(final Collection<Class<?>> current) {
        final Set<Class<?>> retained = new HashSet<>(registered);
        retained.retainAll(componentTypes(current));
        registered = Set.copyOf(retained);
    }

    @Override
    public boolean allows(final Class type) {
        for (TypePermission restriction : restrictions) {
            restriction.allows(type);
        }
        if (type == null || type.isPrimitive()) {
            return true;
        }
        if (type.isArray()) {
            return allows(type.getComponentType());
        }
        if (VALUES.contains(type)) {
            return true;
        }
        if ((type == SerializedLambda.class && hasLambdaBindings) || lambdaTypes.contains(type)) {
            return true;
        }
        if (isForbidden(type)) {
            return false;
        }
        final String module = type.getModule().getName();
        if (module != null && (module.startsWith("java.") || module.startsWith("jdk."))) {
            return false;
        }
        return registered.contains(type);
    }

    public void requireAllowed(final Class<?> type) {
        if (allows(type)) {
            return;
        }
        final ConversionException error = new ConversionException("Campaign save type is not registered or is forbidden");
        error.add("rejected-type", type.getName());
        error.add("type-loader", String.valueOf(type.getClassLoader()));
        final var source = type.getProtectionDomain().getCodeSource();
        error.add("type-source", source == null ? "JDK or unavailable code source" : source.getLocation().toExternalForm());
        throw error;
    }

    private static Set<Class<?>> componentTypes(final Collection<Class<?>> types) {
        final Set<Class<?>> result = new HashSet<>();
        for (Class<?> type : types) {
            while (type.isArray()) {
                type = type.getComponentType();
            }
            result.add(type);
        }
        return Set.copyOf(result);
    }

    private static boolean isForbidden(final Class<?> type) {
        return type == Class.class || type == SerializedLambda.class || LambdaTypes.isLambda(type)
                || ClassLoader.class.isAssignableFrom(type) || Thread.class.isAssignableFrom(type)
                || ThreadGroup.class.isAssignableFrom(type) || Process.class.isAssignableFrom(type)
                || type == Runtime.class || type == ProcessBuilder.class
                || Member.class.isAssignableFrom(type) || Proxy.class.isAssignableFrom(type);
    }
}
