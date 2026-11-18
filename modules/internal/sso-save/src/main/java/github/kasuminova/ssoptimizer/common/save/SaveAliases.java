package github.kasuminova.ssoptimizer.common.save;

import com.thoughtworks.xstream.security.TypePermission;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SaveAliases {
    private final Map<String, Class<?>> aliases = new HashMap<>();
    private final Set<Class<?>> explicitTypes = new HashSet<>();
    private final RegisteredLambdas lambdas = new RegisteredLambdas();
    private volatile SaveTypePermission permission;
    private SavePermissionBaseline baseline;

    public synchronized void rememberPermissions(final List<TypePermission> permissions) {
        if (baseline == null) {
            baseline = new SavePermissionBaseline(permissions);
        }
    }

    public synchronized List<TypePermission> restrictions(final List<TypePermission> current) {
        if (baseline == null) {
            throw new IllegalStateException("The save serializer permission baseline was not recorded");
        }
        return baseline.restrictions(current);
    }

    public synchronized void register(final String name, final Class<?> type) {
        aliases.put(name, type);
        // 读档期间自动发现的注解不得扩大创建序列化器时确定的许可集合。
        if (permission != null) {
            permission.retainRegistrations(currentTypes());
            lambdas.retainAliases(aliases);
        }
    }

    public synchronized void registerTypes(final Class<?>... types) {
        if (permission != null) {
            throw new IllegalStateException("Save types must be registered while configuring the serializer");
        }
        explicitTypes.addAll(List.of(types));
    }

    public RegisteredLambdas lambdas() {
        return lambdas;
    }

    public synchronized SaveTypePermission seal(final List<TypePermission> restrictions) {
        if (permission != null) {
            return null;
        }
        lambdas.seal(aliases);
        permission = new SaveTypePermission(currentTypes(), restrictions, lambdas);
        return permission;
    }

    private Set<Class<?>> currentTypes() {
        final Set<Class<?>> types = new HashSet<>(aliases.values());
        types.addAll(explicitTypes);
        return types;
    }

    public SaveTypePermission permission() {
        return permission;
    }
}
