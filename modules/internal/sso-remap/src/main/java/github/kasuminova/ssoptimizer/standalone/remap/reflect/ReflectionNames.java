package github.kasuminova.ssoptimizer.standalone.remap.reflect;

import java.io.IOException;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 为已挂载的游戏类型翻译反射名称；实际查找和权限检查保留在原调用方。 */
public final class ReflectionNames {
    private static volatile ReflectionIndex active;

    private ReflectionNames() { }

    /** 在模组启动前读取 Tiny 与按实际加载顺序排列的游戏归档。读取失败时不发布索引。 */
    public static synchronized void initialize(Path tiny, List<Path> namedGameJars, ClassLoader gameLoader) throws IOException {
        if (active != null) throw new IllegalStateException("游戏反射名称索引已初始化");
        active = ReflectionIndex.read(tiny, namedGameJars, Objects.requireNonNull(gameLoader));
    }

    public static String className(String name) {
        var index = active;
        return index == null || name == null ? name : index.className(name);
    }

    public static boolean isGameClass(Class<?> type) {
        var index = active;
        if (type == null || index == null) return false;
        while (type.isArray()) type = type.getComponentType();
        return index.owns(type);
    }

    public static String methodName(Class<?> owner, String name, MethodType type) {
        var index = active;
        return index == null || type == null ? name : index.member(owner, name, true, type.descriptorString(), false);
    }

    public static String methodName(Class<?> owner, String name, Class<?>[] parameters) {
        return methodName(owner, name, parameters, ReflectionIndex.Scope.PUBLIC);
    }

    public static String declaredMethodName(Class<?> owner, String name, Class<?>[] parameters) {
        return methodName(owner, name, parameters, ReflectionIndex.Scope.DECLARED);
    }

    private static String methodName(Class<?> owner, String name, Class<?>[] parameters, ReflectionIndex.Scope scope) {
        var index = active;
        if (index == null || owner == null) return name;
        var desc = new StringBuilder("(");
        if (parameters != null) for (var parameter : parameters) {
            if (parameter == null) return name;
            desc.append(parameter.descriptorString());
        }
        return index.member(owner, name, true, desc.append(")").toString(), true, scope);
    }

    public static String fieldName(Class<?> owner, String name, Class<?> type) {
        var index = active;
        return index == null || type == null ? name : index.member(owner, name, false, type.descriptorString(), false);
    }

    public static String fieldName(Class<?> owner, String name) {
        var index = active;
        return index == null ? name : index.member(owner, name, false, "", true, ReflectionIndex.Scope.PUBLIC);
    }

    public static String declaredFieldName(Class<?> owner, String name) {
        var index = active;
        return index == null ? name : index.member(owner, name, false, "", true, ReflectionIndex.Scope.DECLARED);
    }

    public static boolean matches(Member member, String name) {
        if (member == null) return false;
        if (member.getName().equals(name)) return true;
        return aliasMatches(member, name);
    }

    public static boolean matches(Object member, String name) {
        return member instanceof Member actual && matches(actual, name);
    }

    /** 原比较已在调用处执行。仅对实际反射成员的原始名称补充旧名匹配。 */
    public static boolean comparison(Object member, Object expected, Object observedName, boolean originalResult) {
        if (originalResult) return true;
        return expected instanceof String name && observedName instanceof String actual && (member instanceof Field || member instanceof Method)
                && actual.equals(((Member) member).getName()) && aliasMatches((Member) member, name);
    }

    private static boolean aliasMatches(Member member, String oldName) {
        var index = active;
        if (index == null || oldName == null || !index.owns(member.getDeclaringClass())) return false;
        if (member instanceof Field field) {
            return field.getName().equals(index.member(field.getDeclaringClass(), oldName, false, field.getType().descriptorString(), false, ReflectionIndex.Scope.DECLARED));
        }
        if (member instanceof Method method) {
            String desc = MethodType.methodType(method.getReturnType(), method.getParameterTypes()).descriptorString();
            return method.getName().equals(index.member(method.getDeclaringClass(), oldName, true, desc, false, ReflectionIndex.Scope.DECLARED));
        }
        return false;
    }
}
