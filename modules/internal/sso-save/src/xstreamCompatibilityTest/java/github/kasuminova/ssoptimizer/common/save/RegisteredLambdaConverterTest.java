package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.ConversionException;
import com.thoughtworks.xstream.io.xml.StaxDriver;
import com.thoughtworks.xstream.security.ExplicitTypePermission;
import com.thoughtworks.xstream.security.ForbiddenClassException;
import com.thoughtworks.xstream.security.NoPermission;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class RegisteredLambdaConverterTest {
    private static Serializable adder(final int offset) {
        return (IntUnaryOperator & Serializable) value -> value + offset;
    }

    private static Serializable text(final String captured) {
        return (Supplier<String> & Serializable) () -> captured;
    }

    static class Nested {
        static Serializable adder(final int offset) {
            return (IntUnaryOperator & Serializable) value -> value + offset;
        }
    }

    @Test
    void nestedCapturingClassAndTrustedAliasesRoundTrip() {
        final SaveAliases aliases = new SaveAliases();
        aliases.lambdas().register(Nested.adder(3), Nested.class, Nested.class, IntUnaryOperator.class,
                List.of(int.class), arguments -> Nested.adder((Integer) arguments.getFirst()));
        aliases.register("nested-capturer", Nested.class);
        final XStream stream = stream(aliases);
        stream.alias("nested-capturer", Nested.class);
        stream.aliasSystemAttribute("replacement", "resolves-to");
        final String xml = stream.toXML(Nested.adder(3));
        final IntUnaryOperator restored = (IntUnaryOperator) stream.fromXML(xml);
        assertEquals(8, restored.applyAsInt(5));
        final XStream upstream = new XStream(new StaxDriver());
        final String legacy = upstream.toXML(Nested.adder(9))
                .replace("<capturingClass>" + Nested.class.getName() + "</capturingClass>",
                        "<capturingClass>nested-capturer</capturingClass>")
                .replace("resolves-to=", "replacement=");
        assertEquals(13, ((IntUnaryOperator) stream.fromXML(legacy)).applyAsInt(4));
    }

    @Test
    void historicalCapturingAliasesRemainReadableWhileReassignedAndLateAliasesAreRejected() {
        final SaveAliases aliases = new SaveAliases();
        aliases.lambdas().register(Nested.adder(3), Nested.class, Nested.class, IntUnaryOperator.class,
                List.of(int.class), arguments -> Nested.adder((Integer) arguments.getFirst()));
        aliases.register("old-capturer", Nested.class);
        aliases.register("new-capturer", Nested.class);
        aliases.register("reassigned", Nested.class);
        aliases.register("reassigned", String.class);
        final XStream stream = stream(aliases);
        stream.alias("old-capturer", Nested.class);
        stream.alias("new-capturer", Nested.class);
        final String xml = stream.toXML(Nested.adder(3));
        final String oldXml = xml.replace("<capturingClass>new-capturer</capturingClass>",
                "<capturingClass>old-capturer</capturingClass>");
        assertEquals(8, ((IntUnaryOperator) stream.fromXML(xml)).applyAsInt(5));
        assertEquals(8, ((IntUnaryOperator) stream.fromXML(oldXml)).applyAsInt(5));
        for (String invalid : List.of("reassigned", "late-capturer")) {
            assertThrows(ConversionException.class, () -> stream.fromXML(xml.replace(
                    "<capturingClass>new-capturer</capturingClass>",
                    "<capturingClass>" + invalid + "</capturingClass>")));
        }
        aliases.register("old-capturer", String.class);
        aliases.register("late-capturer", Nested.class);
        assertThrows(ConversionException.class, () -> stream.fromXML(oldXml));
        assertThrows(ConversionException.class, () -> stream.fromXML(xml.replace(
                "<capturingClass>new-capturer</capturingClass>", "<capturingClass>late-capturer</capturingClass>")));
    }

    @Test
    void wrongCapturingClassInSameNestIsRejected() {
        final RegisteredLambdas bindings = new RegisteredLambdas();
        assertThrows(IllegalArgumentException.class, () -> bindings.register(Nested.adder(3), getClass(),
                Nested.class, IntUnaryOperator.class, List.of(int.class), arguments -> Nested.adder(3)));
    }

    @Test
    void realJavaLambdaAndEmptySimpleNameAreRecognized() {
        assertTrue(LambdaTypes.isLambda(adder(7).getClass()));
        assertFalse(LambdaTypes.isLambda(null));
        assertFalse(LambdaTypes.isLambda(String.class));
        assertTrue(LambdaTypes.matchesName("", "example.Owner$$Lambda/0x01234"));
        assertTrue(LambdaTypes.matchesName("Owner$$Lambda$4/0x01234", "ignored"));
        assertFalse(LambdaTypes.matchesName("Owner", "example.Owner$$Lambda/0x01234"));
        assertFalse(LambdaTypes.matchesName("", "example.Owner$Ordinary"));
    }

    @Test
    void exactRegisteredBindingRoundTripsCapturedArguments() {
        final AtomicInteger restores = new AtomicInteger();
        final XStream stream = adderStream(restores);
        final String xml = stream.toXML(adder(7));
        final IntUnaryOperator restored = (IntUnaryOperator) stream.fromXML(xml);
        assertEquals(12, restored.applyAsInt(5));
        assertEquals(1, restores.get());
    }

    @Test
    void upstreamSerializedLambdaXmlUsesTheRegisteredFactory() {
        final XStream original = new XStream(new StaxDriver());
        final String legacyXml = original.toXML(adder(13));
        final AtomicInteger restores = new AtomicInteger();
        final IntUnaryOperator restored = (IntUnaryOperator) adderStream(restores).fromXML(legacyXml);
        assertEquals(15, restored.applyAsInt(2));
        assertEquals(1, restores.get());
    }

    @Test
    void nullableCapturedArgumentRoundTrips() {
        final SaveAliases aliases = new SaveAliases();
        aliases.lambdas().register(text(null), getClass(), getClass(), Supplier.class, List.of(String.class),
                arguments -> text((String) arguments.getFirst()));
        final XStream stream = stream(aliases);
        final Supplier<?> restored = (Supplier<?>) stream.fromXML(stream.toXML(text(null)));
        assertNull(restored.get());
    }

    @Test
    void unregisteredBindingCannotInvokeAFactory() {
        final String xml = new XStream(new StaxDriver()).toXML(adder(4));
        final SaveAliases aliases = new SaveAliases();
        aliases.lambdas().register(text("registered"), getClass(), getClass(), Supplier.class, List.of(String.class),
                arguments -> text((String) arguments.getFirst()));
        assertThrows(ConversionException.class, () -> stream(aliases).fromXML(xml));
    }

    @Test
    void changedMetadataIsRejectedBeforeRestoration() {
        final AtomicInteger restores = new AtomicInteger();
        final XStream stream = adderStream(restores);
        final String xml = stream.toXML(adder(4));
        for (String field : List.of("capturingClass", "functionalInterfaceClass", "functionalInterfaceMethodName",
                "functionalInterfaceMethodSignature", "implClass", "implMethodName", "implMethodSignature",
                "instantiatedMethodType")) {
            final String changed = xml.replaceFirst("<" + field + ">[^<]*</" + field + ">",
                    "<" + field + ">unregistered</" + field + ">");
            assertThrows(ConversionException.class, () -> stream.fromXML(changed), field);
        }
        assertThrows(ConversionException.class,
                () -> stream.fromXML(xml.replaceFirst("<implMethodKind>[^<]*</implMethodKind>",
                        "<implMethodKind>invalid</implMethodKind>")));
        assertEquals(0, restores.get());
    }

    @Test
    void malformedMetadataAndCapturedArgumentsAreRejectedBeforeFactory() {
        final AtomicInteger restores = new AtomicInteger();
        final XStream stream = adderStream(restores);
        final String xml = stream.toXML(adder(4));
        final List<String> malformed = List.of(
                xml.replace("<int>4</int>", "<string>wrong type</string>"),
                xml.replace("<int>4</int>", ""),
                xml.replace("<int>4</int>", "<int>4</int><int>5</int>"),
                xml.replace("<int>4</int>", "<null/>"),
                xml.replace("<capturedArgs>", "<implMethodKind>6</implMethodKind><capturedArgs>"),
                xml.replaceFirst("<implClass>[^<]*</implClass>", ""));
        for (String input : malformed) {
            assertThrows(ConversionException.class, () -> stream.fromXML(input));
        }
        assertEquals(0, restores.get());
    }

    @Test
    void explicitDenialStillRejectsARegisteredLambdaRepresentation() {
        final SaveAliases aliases = new SaveAliases();
        aliases.lambdas().register(adder(4), getClass(), getClass(), IntUnaryOperator.class, List.of(int.class),
                arguments -> adder((Integer) arguments.getFirst()));
        final var permission = aliases.seal(List.of(new NoPermission(
                new ExplicitTypePermission(new Class<?>[]{SerializedLambda.class}))));
        final XStream stream = SaveTypePermissionTest.stream(permission);
        stream.registerConverter(new RegisteredLambdaConverter(stream.getMapper(), aliases.lambdas()), XStream.PRIORITY_VERY_HIGH);
        final String xml = new XStream(new StaxDriver()).toXML(adder(4));
        assertThrows(ForbiddenClassException.class, () -> stream.fromXML(xml));
    }

    @Test
    void factoryMustReturnTheRegisteredRuntimeBinding() {
        final SaveAliases aliases = new SaveAliases();
        aliases.lambdas().register(adder(4), getClass(), getClass(), IntUnaryOperator.class, List.of(int.class),
                arguments -> text("wrong binding"));
        final XStream stream = stream(aliases);
        final String xml = stream.toXML(adder(4));
        assertThrows(ConversionException.class, () -> stream.fromXML(xml));
    }

    private XStream adderStream(final AtomicInteger restores) {
        final SaveAliases aliases = new SaveAliases();
        aliases.lambdas().register(adder(0), getClass(), getClass(), IntUnaryOperator.class, List.of(int.class), arguments -> {
            restores.incrementAndGet();
            return adder((Integer) arguments.getFirst());
        });
        return stream(aliases);
    }

    private static XStream stream(final SaveAliases aliases) {
        final SaveTypePermission permission = aliases.seal(List.of());
        final XStream stream = SaveTypePermissionTest.stream(permission);
        stream.registerConverter(new RegisteredLambdaConverter(stream.getMapper(), aliases.lambdas()), XStream.PRIORITY_VERY_HIGH);
        return stream;
    }
}
