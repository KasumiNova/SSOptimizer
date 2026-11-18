package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.security.ArrayTypePermission;
import com.thoughtworks.xstream.security.ExplicitTypePermission;
import com.thoughtworks.xstream.security.InterfaceTypePermission;
import com.thoughtworks.xstream.security.NoPermission;
import com.thoughtworks.xstream.security.NoTypePermission;
import com.thoughtworks.xstream.security.NullPermission;
import com.thoughtworks.xstream.security.PrimitiveTypePermission;
import com.thoughtworks.xstream.security.RegExpTypePermission;
import com.thoughtworks.xstream.security.TypeHierarchyPermission;
import com.thoughtworks.xstream.security.TypePermission;
import com.thoughtworks.xstream.security.WildcardTypePermission;

import java.util.List;
import java.util.Set;

public final class SavePermissionBaseline {
    private static final Set<Class<?>> SUPPORTED = Set.of(ArrayTypePermission.class, ExplicitTypePermission.class,
            InterfaceTypePermission.class, NoPermission.class, NoTypePermission.class, NullPermission.class,
            PrimitiveTypePermission.class, RegExpTypePermission.class, TypeHierarchyPermission.class,
            WildcardTypePermission.class);

    private final List<TypePermission> initial;

    public SavePermissionBaseline(final List<TypePermission> initial) {
        this.initial = List.copyOf(initial);
    }

    public List<TypePermission> restrictions(final List<TypePermission> current) {
        final int offset = current.size() - initial.size();
        if (offset < 0) {
            throw unsupported("the configuration replaced the original permission chain");
        }
        for (int i = 0; i < initial.size(); i++) {
            if (current.get(offset + i) != initial.get(i)) {
                throw unsupported("the configuration reset or reordered the original permission chain");
            }
        }
        for (TypePermission permission : current) {
            if (!SUPPORTED.contains(permission.getClass())) {
                throw unsupported("custom permission implementation " + permission.getClass().getName());
            }
        }
        return current.stream().filter(permission -> permission instanceof NoPermission).toList();
    }

    private static IllegalStateException unsupported(final String reason) {
        return new IllegalStateException("Standalone save permissions cannot preserve " + reason
                + "; use explicit save type registration with upstream XStream 1.4.21");
    }
}
