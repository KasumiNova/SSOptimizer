package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.ConversionException;
import com.thoughtworks.xstream.io.xml.StaxDriver;
import com.thoughtworks.xstream.security.ExplicitTypePermission;
import com.thoughtworks.xstream.security.ForbiddenClassException;
import com.thoughtworks.xstream.security.NoPermission;
import com.thoughtworks.xstream.security.NoTypePermission;
import com.thoughtworks.xstream.security.TypePermission;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.awt.Color;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SaveTypePermissionTest {
    static class Payload {
        int value;
    }

    static class ChildPayload extends Payload {
    }

    static class UnregisteredList extends ArrayList<Object> {
    }

    static class Loader extends ClassLoader {
    }

    static class Values {
        Random random = new Random(123);
        Color color = new Color(23, 47, 91, 127);
        int[] numbers = {2, 7};
        Payload[] payloads = {};
        Map<String, String> attributes = new LinkedHashMap<>();
    }

    @Test
    void registrationAllowsExactTypesAndArrayElementsOnly() {
        final SaveAliases aliases = new SaveAliases();
        aliases.register("payloads", Payload[][].class);
        final SaveTypePermission permission = aliases.seal(List.of());
        assertTrue(permission.allows(Payload.class));
        assertTrue(permission.allows(Payload[][].class));
        assertTrue(permission.allows(int[][].class));
        assertFalse(permission.allows(ChildPayload.class));
        assertFalse(permission.allows(ChildPayload[].class));
        assertFalse(permission.allows(UnregisteredList.class));
    }

    @Test
    void overwrittenAliasesAndLateAnnotationsCannotGrantTypes() {
        final SaveAliases aliases = new SaveAliases();
        aliases.register("item", Payload.class);
        aliases.register("item", ChildPayload.class);
        final SaveTypePermission permission = aliases.seal(List.of());
        assertFalse(permission.allows(Payload.class));
        assertTrue(permission.allows(ChildPayload.class));
        aliases.register("item", Payload.class);
        assertFalse(permission.allows(ChildPayload.class));
        assertFalse(permission.allows(Payload.class));
        aliases.register("annotation", UnregisteredList.class);
        assertFalse(permission.allows(UnregisteredList.class));
        assertThrows(IllegalStateException.class, () -> aliases.registerTypes(Payload.class));
    }

    @Test
    void explicitRegistrationSurvivesAliasReplacement() {
        final SaveAliases aliases = new SaveAliases();
        aliases.registerTypes(Payload.class);
        aliases.register("item", Payload.class);
        final SaveTypePermission permission = aliases.seal(List.of());
        aliases.register("item", ChildPayload.class);
        assertTrue(permission.allows(Payload.class));
        assertFalse(permission.allows(ChildPayload.class));
    }

    @Test
    void dangerousRuntimeTypesStayForbiddenEvenWhenRegistered() {
        final List<Class<?>> types = List.of(Class.class, ClassLoader.class, Loader.class, Thread.class,
                Runtime.class, ProcessBuilder.class, Proxy.class, SerializedLambda.class);
        final SaveTypePermission permission = new SaveTypePermission(types, List.of(), new RegisteredLambdas());
        for (Class<?> type : types) {
            assertFalse(permission.allows(type), type.getName());
        }
    }

    @Test
    void sameBinaryNameFromAnotherLoaderDoesNotInheritPermission() {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/SavePayload", null, "java/lang/Object", null);
        writer.visitEnd();
        class Definer extends ClassLoader {
            Class<?> define() {
                final byte[] bytes = writer.toByteArray();
                return defineClass("example.SavePayload", bytes, 0, bytes.length);
            }
        }
        final Class<?> registered = new Definer().define();
        final Class<?> other = new Definer().define();
        final SaveTypePermission permission = new SaveTypePermission(List.of(registered), List.of(), new RegisteredLambdas());
        assertTrue(permission.allows(registered));
        assertFalse(permission.allows(other));
    }

    @Test
    void explicitModDenialWinsOverAliasAndValueRegistration() {
        final TypePermission denial = new NoPermission(new ExplicitTypePermission(new Class<?>[]{Payload.class, Random.class}));
        final SaveTypePermission permission = new SaveTypePermission(List.of(Payload.class), List.of(denial), new RegisteredLambdas());
        assertThrows(ForbiddenClassException.class, () -> permission.requireAllowed(Payload.class));
        assertThrows(ForbiddenClassException.class, () -> permission.requireAllowed(Random.class));
        final XStream stream = stream(permission);
        stream.alias("payload", Payload.class);
        assertThrows(ForbiddenClassException.class, () -> stream.fromXML("<payload><value>2</value></payload>"));
    }

    @Test
    void upstreamCollectionHierarchyCannotAdmitAnUnknownSubclass() {
        final SaveTypePermission permission = new SaveTypePermission(List.of(), List.of(), new RegisteredLambdas());
        final XStream stream = stream(permission);
        stream.alias("unknown-list", UnregisteredList.class);
        assertThrows(ConversionException.class, () -> stream.fromXML("<unknown-list/>"));
        assertEquals(List.of(3), stream.fromXML("<list><int>3</int></list>"));
    }

    @Test
    void approvedRandomMapAndArrayValuesRoundTrip() {
        final SaveTypePermission permission = new SaveTypePermission(List.of(Values.class, Payload[].class),
                List.of(), new RegisteredLambdas());
        final XStream stream = stream(permission);
        stream.alias("values", Values.class);
        final Values original = new Values();
        final Payload payload = new Payload();
        payload.value = 9;
        original.payloads = new Payload[]{payload};
        original.attributes.put("a", "b");
        final Values restored = (Values) stream.fromXML(stream.toXML(original));
        assertArrayEquals(original.numbers, restored.numbers);
        assertEquals(9, restored.payloads[0].value);
        assertEquals(original.attributes, restored.attributes);
        assertEquals(original.color, restored.color);
        assertEquals(original.random.nextLong(), restored.random.nextLong());
        assertTrue(permission.allows(Map.Entry.class));
    }

    static XStream stream(final SaveTypePermission permission) {
        final XStream stream = new XStream(new StaxDriver());
        stream.addPermission(NoTypePermission.NONE);
        stream.addPermission(type -> {
            permission.requireAllowed(type);
            return true;
        });
        return stream;
    }
}
