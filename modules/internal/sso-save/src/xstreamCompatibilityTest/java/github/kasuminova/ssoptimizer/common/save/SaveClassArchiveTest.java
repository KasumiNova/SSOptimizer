package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.converters.ConversionException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.net.URI;
import java.net.URL;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.security.cert.Certificate;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SaveClassArchiveTest {
    private static final String JAR = "file:/RC8%20game/named/0000-game.jar";
    private enum Layer { BELOW, ABOVE }

    @Test
    void directAndPerClassUrlsIdentifyTheSameArchive() throws Exception {
        final Definer loader = new Definer();
        final Class<?> anchor = loader.define("game.Sector", JAR);
        final Class<?> entity = loader.define("game.Market$Data", "jar:" + JAR + "!/game/Market$Data.class");
        assertTrue(SaveClassArchive.sameAs(entity, anchor));
        assertTrue(SaveClassArchive.sameAs(anchor, entity));
    }

    @Test
    void admissionRequiresBothTheDefiningLoaderAndTheArchive() throws Exception {
        final Definer loader = new Definer();
        final Class<?> anchor = loader.define("game.Sector", "jar:" + JAR + "!/game/Sector.class");
        final Class<?> original = loader.define("game.Faction", JAR);
        final Class<?> sameName = new Definer().define("game.Faction", JAR);
        final Class<?> otherArchive = loader.define("game.Other", "file:/mods/0000-game.jar");
        assertTrue(SaveClassArchive.sameAs(original, anchor));
        assertFalse(SaveClassArchive.sameAs(sameName, anchor));
        assertFalse(SaveClassArchive.sameAs(otherArchive, anchor));
    }

    @Test
    void missingLocationsExplodedDirectoriesAndWrongClassEntriesAreRejected() throws Exception {
        final Definer loader = new Definer();
        final Class<?> anchor = loader.define("game.Sector", JAR);
        final Class<?> noSource = loader.define("game.Script", null);
        assertFalse(SaveClassArchive.sameAs(noSource, anchor));
        assertFalse(SaveClassArchive.sameAs(loader.define("game.Market", "jar:" + JAR + "!/game/Sector.class"), anchor));
        final Class<?> directory = loader.define("game.Directory", "file:/classes/");
        assertFalse(SaveClassArchive.sameAs(directory, directory));
        final SaveTypePermission rules = new SaveAliases().seal(List.of());
        final ConversionException error = assertThrows(ConversionException.class, () -> rules.requireAllowed(noSource));
        assertEquals(noSource.getName(), error.get("rejected-type"));
    }

    @Test
    void enumSetValuesRoundTripOnlyWhenTheirElementTypeIsApproved() {
        final SaveTypePermission allowed = new SaveTypePermission(List.of(Layer.class), List.of(), new RegisteredLambdas());
        final var stream = SaveTypePermissionTest.stream(allowed);
        final var original = EnumSet.of(Layer.ABOVE);
        allowed.requireAllowed(original.getClass());
        final String xml = stream.toXML(original);
        assertEquals(original, stream.fromXML(xml));
        final var restricted = SaveTypePermissionTest.stream(new SaveAliases().seal(List.of()));
        assertThrows(ConversionException.class, () -> restricted.fromXML(xml));
    }

    private static final class Definer extends SecureClassLoader {
        Class<?> define(final String name, final String location) throws Exception {
            final ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
            writer.visitEnd();
            final byte[] bytes = writer.toByteArray();
            final URL url = location == null ? null : URI.create(location).toURL();
            return defineClass(name, bytes, 0, bytes.length, new CodeSource(url, (Certificate[]) null));
        }
    }
}
