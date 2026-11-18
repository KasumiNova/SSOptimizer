package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.security.ExplicitTypePermission;
import com.thoughtworks.xstream.security.ForbiddenClassException;
import com.thoughtworks.xstream.security.NoPermission;
import com.thoughtworks.xstream.security.NoTypePermission;
import com.thoughtworks.xstream.security.TypeHierarchyPermission;
import com.thoughtworks.xstream.security.TypePermission;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SavePermissionBaselineTest {
    @Test
    void knownAdditionsPreserveExplicitDenials() {
        final TypePermission hierarchy = new TypeHierarchyPermission(Collection.class);
        final SavePermissionBaseline baseline = new SavePermissionBaseline(List.of(hierarchy, NoTypePermission.NONE));
        final TypePermission deny = new NoPermission(new ExplicitTypePermission(new Class<?>[]{String.class}));
        final List<TypePermission> restrictions = baseline.restrictions(
                List.of(deny, new ExplicitTypePermission(new Class<?>[]{Integer.class}), hierarchy, NoTypePermission.NONE));
        assertThrows(ForbiddenClassException.class, () -> restrictions.getFirst().allows(String.class));
        assertFalse(restrictions.getFirst().allows(Integer.class));
    }

    @Test
    void resetAndReorderedChainsAreRejected() {
        final TypePermission hierarchy = new TypeHierarchyPermission(Collection.class);
        final SavePermissionBaseline baseline = new SavePermissionBaseline(List.of(hierarchy, NoTypePermission.NONE));
        assertThrows(IllegalStateException.class, () -> baseline.restrictions(List.of(NoTypePermission.NONE)));
        assertThrows(IllegalStateException.class,
                () -> baseline.restrictions(List.of(NoTypePermission.NONE, hierarchy)));
        assertThrows(IllegalStateException.class,
                () -> baseline.restrictions(List.of(new TypeHierarchyPermission(Collection.class), NoTypePermission.NONE)));
    }

    @Test
    void unknownCustomRulesAreRejectedWithoutEvaluatingThem() {
        final AtomicInteger calls = new AtomicInteger();
        final TypePermission custom = type -> {
            calls.incrementAndGet();
            return false;
        };
        final SavePermissionBaseline baseline = new SavePermissionBaseline(List.of(NoTypePermission.NONE));
        assertThrows(IllegalStateException.class,
                () -> baseline.restrictions(List.of(custom, NoTypePermission.NONE)));
        assertEquals(0, calls.get());
    }
}
