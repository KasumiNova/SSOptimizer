package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.converters.ConversionException;
import com.thoughtworks.xstream.core.util.SerializationMembers;
import github.kasuminova.ssoptimizer.api.save.SaveLambdaFactory;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RegisteredLambdas {
    private final Map<Key, Binding> bindings = new HashMap<>();
    private final Map<Class<?>, Binding> runtimeTypes = new HashMap<>();
    private Map<String, Class<?>> capturingAliases = Map.of();
    private boolean sealed;

    public synchronized void register(final Serializable prototype, final Class<?> capturingClass,
                                      final Class<?> implementationClass, final Class<?> functionalInterface, final List<Class<?>> capturedTypes,
                                      final SaveLambdaFactory factory) {
        if (sealed) {
            throw new IllegalStateException("Lambda bindings must be registered while configuring the save serializer");
        }
        Objects.requireNonNull(factory, "factory");
        if (!LambdaTypes.isLambda(prototype.getClass())) {
            throw new IllegalArgumentException("A compiler-generated serializable lambda is required");
        }
        final SerializedLambda serialized = describe(prototype);
        if (prototype.getClass().getClassLoader() != capturingClass.getClassLoader()
                || prototype.getClass().getNestHost() != capturingClass.getNestHost()
                || !serialized.getCapturingClass().equals(internalName(capturingClass))
                || !serialized.getImplClass().equals(internalName(implementationClass))
                || !serialized.getFunctionalInterfaceClass().equals(internalName(functionalInterface))
                || !functionalInterface.isInstance(prototype)) {
            throw new IllegalArgumentException("Lambda registration classes do not match the trusted prototype");
        }
        final Key key = Key.of(serialized);
        final Binding binding = new Binding(key, capturingClass, prototype.getClass(), List.copyOf(capturedTypes), factory);
        binding.checkArguments(arguments(serialized));
        if (bindings.containsKey(key) || runtimeTypes.containsKey(prototype.getClass())) {
            throw new IllegalArgumentException("Lambda binding is already registered: " + key);
        }
        bindings.put(key, binding);
        runtimeTypes.put(prototype.getClass(), binding);
    }

    synchronized void seal(final Map<String, Class<?>> aliases) {
        capturingAliases = Map.copyOf(aliases);
        sealed = true;
    }

    synchronized void retainAliases(final Map<String, Class<?>> aliases) {
        final Map<String, Class<?>> retained = new HashMap<>(capturingAliases);
        retained.entrySet().removeIf(entry -> aliases.get(entry.getKey()) != entry.getValue());
        capturingAliases = Map.copyOf(retained);
    }

    synchronized Set<Class<?>> runtimeTypes() {
        return Set.copyOf(runtimeTypes.keySet());
    }

    synchronized boolean isEmpty() {
        return bindings.isEmpty();
    }

    synchronized Binding require(final Key key) {
        final Binding binding = bindings.get(key);
        if (binding == null) {
            final ConversionException error = new ConversionException("Lambda binding is not registered");
            error.add("lambda-binding", key.toString());
            throw error;
        }
        return binding;
    }

    synchronized Binding requireXml(final Key key) {
        Binding selected = null;
        for (Binding binding : bindings.values()) {
            if ((key.capturingClass().equals(binding.key().capturingClass())
                    || capturingAliases.get(key.capturingClass()) == binding.capturingClass())
                    && key.withCapturingClass(binding.key().capturingClass()).equals(binding.key())) {
                if (selected != null) {
                    throw new ConversionException("Lambda capturing class alias is ambiguous: " + key.capturingClass());
                }
                selected = binding;
            }
        }
        if (selected == null) {
            final ConversionException error = new ConversionException("Lambda binding is not registered");
            error.add("lambda-binding", key.toString());
            throw error;
        }
        return selected;
    }

    SerializedLambda checkedDescription(final Object value) {
        final SerializedLambda serialized;
        if (value instanceof SerializedLambda replacement) {
            serialized = replacement;
        } else {
            synchronized (this) {
                if (!runtimeTypes.containsKey(value.getClass())) {
                    throw new ConversionException("Lambda runtime class is not registered: " + value.getClass().getName());
                }
            }
            serialized = describe(value);
        }
        require(Key.of(serialized)).checkArguments(arguments(serialized));
        return serialized;
    }

    private static SerializedLambda describe(final Object prototype) {
        final Object replacement = new SerializationMembers().callWriteReplace(prototype);
        if (!(replacement instanceof SerializedLambda serialized)) {
            throw new IllegalArgumentException("Lambda writeReplace did not produce SerializedLambda");
        }
        return serialized;
    }

    static List<Object> arguments(final SerializedLambda value) {
        final List<Object> arguments = new ArrayList<>(value.getCapturedArgCount());
        for (int i = 0; i < value.getCapturedArgCount(); i++) {
            arguments.add(value.getCapturedArg(i));
        }
        return arguments;
    }

    private static String internalName(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    record Key(String capturingClass, String functionalInterfaceClass, String functionalInterfaceMethodName,
               String functionalInterfaceMethodSignature, String implClass, String implMethodName,
               String implMethodSignature, int implMethodKind, String instantiatedMethodType) {
        Key withCapturingClass(final String name) {
            return new Key(name, functionalInterfaceClass, functionalInterfaceMethodName, functionalInterfaceMethodSignature,
                    implClass, implMethodName, implMethodSignature, implMethodKind, instantiatedMethodType);
        }

        static Key of(final SerializedLambda value) {
            return new Key(value.getCapturingClass().replace('/', '.'), value.getFunctionalInterfaceClass(),
                    value.getFunctionalInterfaceMethodName(), value.getFunctionalInterfaceMethodSignature(),
                    value.getImplClass(), value.getImplMethodName(), value.getImplMethodSignature(),
                    value.getImplMethodKind(), value.getInstantiatedMethodType());
        }
    }

    record Binding(Key key, Class<?> capturingClass, Class<?> runtimeType,
                   List<Class<?>> capturedTypes, SaveLambdaFactory factory) {
        void checkArgumentType(final int index, final Class<?> actual) {
            final Class<?> expected = capturedTypes.get(index);
            if (actual == null && !expected.isPrimitive()) {
                return;
            }
            if (box(expected) != box(actual)) {
                throw new ConversionException("Lambda captured argument " + index + " requires exact type " + expected.getName());
            }
        }

        void checkArguments(final List<Object> arguments) {
            if (arguments.size() != capturedTypes.size()) {
                throw new ConversionException("Lambda captured argument count does not match the registered binding");
            }
            for (int i = 0; i < arguments.size(); i++) {
                final Object value = arguments.get(i);
                checkArgumentType(i, value == null ? null : value.getClass());
            }
        }

        Serializable restore(final List<Object> arguments) {
            checkArguments(arguments);
            final Serializable restored = factory.restore(Collections.unmodifiableList(new ArrayList<>(arguments)));
            if (restored == null || restored.getClass() != runtimeType) {
                throw new ConversionException("Lambda factory returned a different runtime binding");
            }
            return restored;
        }

        private static Class<?> box(final Class<?> type) {
            if (type == null || !type.isPrimitive()) {
                return type;
            }
            return switch (type.getName()) {
                case "boolean" -> Boolean.class;
                case "byte" -> Byte.class;
                case "short" -> Short.class;
                case "char" -> Character.class;
                case "int" -> Integer.class;
                case "long" -> Long.class;
                case "float" -> Float.class;
                case "double" -> Double.class;
                default -> Void.class;
            };
        }
    }
}
