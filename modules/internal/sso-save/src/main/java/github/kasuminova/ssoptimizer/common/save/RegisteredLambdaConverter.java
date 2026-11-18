package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.converters.ConversionException;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.core.util.HierarchicalStreams;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import com.thoughtworks.xstream.mapper.Mapper;

import java.lang.invoke.SerializedLambda;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RegisteredLambdaConverter implements Converter {
    private static final Set<String> HEADER_FIELDS = Set.of("capturingClass", "functionalInterfaceClass",
            "functionalInterfaceMethodName", "functionalInterfaceMethodSignature", "implClass",
            "implMethodName", "implMethodSignature", "implMethodKind", "instantiatedMethodType");

    private final Mapper mapper;
    private final RegisteredLambdas bindings;

    public RegisteredLambdaConverter(final Mapper mapper, final RegisteredLambdas bindings) {
        this.mapper = mapper;
        this.bindings = bindings;
    }

    @Override
    public boolean canConvert(final Class type) {
        return type == SerializedLambda.class || LambdaTypes.isLambda(type);
    }

    @Override
    public void marshal(final Object source, final HierarchicalStreamWriter writer, final MarshallingContext context) {
        final SerializedLambda lambda = bindings.checkedDescription(source);
        final RegisteredLambdas.Key key = RegisteredLambdas.Key.of(lambda);
        final String replacementAttribute = mapper.aliasForSystemAttribute("resolves-to");
        if (replacementAttribute == null) {
            throw new ConversionException("Registered lambda serialization requires the resolves-to system attribute");
        }
        final String capturingAlias = mapper.serializedClass(bindings.require(key).capturingClass());
        bindings.requireXml(key.withCapturingClass(capturingAlias));
        writer.addAttribute(replacementAttribute, mapper.serializedClass(SerializedLambda.class));
        field(writer, "capturingClass", capturingAlias);
        field(writer, "functionalInterfaceClass", key.functionalInterfaceClass());
        field(writer, "functionalInterfaceMethodName", key.functionalInterfaceMethodName());
        field(writer, "functionalInterfaceMethodSignature", key.functionalInterfaceMethodSignature());
        field(writer, "implClass", key.implClass());
        field(writer, "implMethodName", key.implMethodName());
        field(writer, "implMethodSignature", key.implMethodSignature());
        field(writer, "implMethodKind", Integer.toString(key.implMethodKind()));
        field(writer, "instantiatedMethodType", key.instantiatedMethodType());
        writer.startNode("capturedArgs");
        for (Object value : RegisteredLambdas.arguments(lambda)) {
            writer.startNode(mapper.serializedClass(value == null ? null : value.getClass()));
            if (value != null) {
                context.convertAnother(value);
            }
            writer.endNode();
        }
        writer.endNode();
    }

    @Override
    public Object unmarshal(final HierarchicalStreamReader reader, final UnmarshallingContext context) {
        final Map<String, String> header = new HashMap<>();
        RegisteredLambdas.Binding binding = null;
        List<Object> arguments = null;
        while (reader.hasMoreChildren()) {
            reader.moveDown();
            final String field = reader.getNodeName();
            if (field.equals("capturedArgs")) {
                if (arguments != null || header.size() != HEADER_FIELDS.size()) {
                    throw new ConversionException("Lambda metadata must appear exactly once before capturedArgs");
                }
                binding = bindings.requireXml(parseKey(header));
                arguments = readArguments(reader, context, binding);
            } else {
                if (arguments != null || !HEADER_FIELDS.contains(field) || header.containsKey(field)
                        || reader.hasMoreChildren()) {
                    throw new ConversionException("Unexpected or repeated lambda metadata: " + field);
                }
                header.put(field, reader.getValue());
            }
            reader.moveUp();
        }
        if (binding == null || arguments == null) {
            throw new ConversionException("Lambda capturedArgs or metadata is missing");
        }
        return binding.restore(arguments);
    }

    private List<Object> readArguments(final HierarchicalStreamReader reader, final UnmarshallingContext context,
                                       final RegisteredLambdas.Binding binding) {
        final List<Object> arguments = new ArrayList<>();
        while (reader.hasMoreChildren()) {
            if (arguments.size() == binding.capturedTypes().size()) {
                throw new ConversionException("Too many lambda captured arguments");
            }
            reader.moveDown();
            final Class<?> type = HierarchicalStreams.readClassType(reader, mapper);
            binding.checkArgumentType(arguments.size(), type == Mapper.Null.class ? null : type);
            final Object value = type == Mapper.Null.class ? null : context.convertAnother(null, type);
            arguments.add(value);
            reader.moveUp();
        }
        binding.checkArguments(arguments);
        return arguments;
    }

    private static RegisteredLambdas.Key parseKey(final Map<String, String> header) {
        final int kind;
        try {
            kind = Integer.parseInt(header.get("implMethodKind"));
        } catch (NumberFormatException error) {
            throw new ConversionException("Invalid lambda implementation method kind", error);
        }
        return new RegisteredLambdas.Key(header.get("capturingClass"), header.get("functionalInterfaceClass"),
                header.get("functionalInterfaceMethodName"), header.get("functionalInterfaceMethodSignature"),
                header.get("implClass"), header.get("implMethodName"), header.get("implMethodSignature"), kind,
                header.get("instantiatedMethodType"));
    }

    private static void field(final HierarchicalStreamWriter writer, final String name, final String value) {
        writer.startNode(name);
        writer.setValue(value);
        writer.endNode();
    }
}
