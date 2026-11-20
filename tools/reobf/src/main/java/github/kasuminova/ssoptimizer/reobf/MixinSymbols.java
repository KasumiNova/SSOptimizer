package github.kasuminova.ssoptimizer.reobf;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class MixinSymbols {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";

    private MixinSymbols() {}

    static void rewriteAliases(ClassNode mixin, ReobfMappings mappings) {
        List<String> targets = targets(mixin);
        if (targets.isEmpty()) return;
        for (var field : mixin.fields) {
            rewriteAliases(annotation(field.visibleAnnotations, field.invisibleAnnotations, SHADOW),
                    targets, field.desc, false, mappings);
        }
        for (var method : mixin.methods) {
            rewriteAliases(annotation(method.visibleAnnotations, method.invisibleAnnotations, SHADOW),
                    targets, method.desc, true, mappings);
        }
    }

    private static void rewriteAliases(AnnotationNode shadow, List<String> targets, String desc,
                                       boolean method, ReobfMappings mappings) {
        int index = valueIndex(shadow, "aliases");
        if (index < 0) return;
        var aliases = new LinkedHashSet<String>();
        for (Object alias : (List<?>) shadow.values.get(index)) {
            String name = (String) alias;
            for (String target : targets) {
                aliases.add(method ? mappings.methodName(target, name, desc) : mappings.fieldName(target, name, desc));
            }
        }
        shadow.values.set(index, new ArrayList<>(aliases));
    }

    static void validate(ClassNode mixin, ReobfMappings mappings, GameClasses original) {
        List<String> targets = targets(mixin);
        for (String target : targets) {
            if (!original.contains(target) && mappings.isGameClass(target)) {
                throw new IllegalArgumentException("Mixin " + mixin.name + " targets a class absent from the original game: " + target);
            }
            if (!original.contains(target)) continue;
            for (var field : mixin.fields) {
                validateShadow(mixin.name, target, field.name, field.desc, false,
                        annotation(field.visibleAnnotations, field.invisibleAnnotations, SHADOW), original);
            }
            for (var method : mixin.methods) {
                validateShadow(mixin.name, target, method.name, method.desc, true,
                        annotation(method.visibleAnnotations, method.invisibleAnnotations, SHADOW), original);
                validateInjection(mixin.name, target, method.visibleAnnotations, original);
                validateInjection(mixin.name, target, method.invisibleAnnotations, original);
                validatePoints(mixin.name, method.visibleAnnotations, mappings, original);
                validatePoints(mixin.name, method.invisibleAnnotations, mappings, original);
            }
        }
    }

    private static void validateShadow(String mixin, String target, String name, String desc, boolean method,
                                       AnnotationNode shadow, GameClasses original) {
        if (shadow == null) return;
        var prefix = value(shadow, "prefix") instanceof String explicit ? explicit : "shadow$";
        String targetName = name.startsWith(prefix) ? name.substring(prefix.length()) : name;
        if (original.hasMember(target, targetName, desc, method)) return;
        if (value(shadow, "aliases") instanceof List<?> aliases) {
            for (Object alias : aliases) {
                if (original.hasMember(target, (String) alias, desc, method)) return;
            }
        }
        throw new IllegalArgumentException("Mixin " + mixin + " has no original @Shadow target " + target + '.' + name + ' ' + desc);
    }

    private static void validateInjection(String mixin, String target, List<AnnotationNode> annotations, GameClasses original) {
        if (annotations == null) return;
        for (AnnotationNode annotation : annotations) {
            if (!(value(annotation, "method") instanceof List<?> selectors) || selectors.isEmpty()) continue;
            if (!annotation.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                    && !annotation.desc.startsWith("Lcom/llamalad7/mixinextras/")) continue;
            boolean matched = selectors.stream().map(String.class::cast).anyMatch(selector -> matchesMethod(selector, target, original));
            if (!matched) {
                throw new IllegalArgumentException("Mixin " + mixin + " has no original injection method in " + target + ": " + selectors);
            }
        }
    }

    private static void validatePoints(String mixin, Object value, ReobfMappings mappings, GameClasses original) {
        if (value instanceof List<?> values) {
            values.forEach(item -> validatePoints(mixin, item, mappings, original));
        } else if (value instanceof AnnotationNode annotation) {
            if (AT.equals(annotation.desc)) {
                validatePoint(mixin, annotation, mappings, original);
            } else {
                validatePoints(mixin, annotation.values, mappings, original);
            }
        }
    }

    private static void validatePoint(String mixin, AnnotationNode at, ReobfMappings mappings, GameClasses original) {
        if (!(value(at, "target") instanceof String selector) || !selector.startsWith("L")) return;
        int ownerEnd = selector.indexOf(';');
        if (ownerEnd < 0 || selector.contains("->") || "NEW".equals(value(at, "value"))) return;
        String owner = selector.substring(1, ownerEnd);
        if (!mappings.isGameClass(owner) && !original.contains(owner)) return;
        if (!original.contains(owner)) {
            throw new IllegalArgumentException("Mixin " + mixin + " has no original @At owner: " + selector);
        }
        int memberStart = ownerEnd + 1;
        int descriptor = selector.indexOf('(', memberStart);
        boolean method = descriptor >= 0;
        if (!method) descriptor = selector.indexOf(':', memberStart);
        if (descriptor < 0) return;
        String name = selector.substring(memberStart, descriptor).replaceFirst("(?:[+*]|\\{[^}]*})$", "");
        String desc = selector.substring(method ? descriptor : descriptor + 1);
        if (!name.isEmpty() && !original.hasMember(owner, name, desc, method)) {
            throw new IllegalArgumentException("Mixin " + mixin + " has no original @At member: " + selector);
        }
    }

    private static boolean matchesMethod(String selector, String target, GameClasses original) {
        String text = selector.replaceAll("\\s", "");
        if (text.startsWith("@") || text.endsWith("/") || text.contains("->")) return true;
        int descriptor = text.indexOf('(');
        String desc = descriptor >= 0 ? text.substring(descriptor) : null;
        String name = descriptor >= 0 ? text.substring(0, descriptor) : text;
        if (name.startsWith("L") && name.contains(";")) {
            int separator = name.indexOf(';');
            if (!target.equals(name.substring(1, separator))) return false;
            name = name.substring(separator + 1);
        }
        name = name.replaceFirst("(?:[+*]|\\{[^}]*})$", "");
        return name.isEmpty() || original.declares(target, name, desc, true);
    }

    private static List<String> targets(ClassNode node) {
        AnnotationNode mixin = annotation(node.visibleAnnotations, node.invisibleAnnotations, MIXIN);
        if (mixin == null) return List.of();
        var targets = new LinkedHashSet<String>();
        if (value(mixin, "value") instanceof List<?> types) {
            for (Object type : types) targets.add(((Type) type).getInternalName());
        }
        if (value(mixin, "targets") instanceof List<?> names) {
            for (Object name : names) targets.add(((String) name).replace('.', '/'));
        }
        return List.copyOf(targets);
    }

    private static Object value(AnnotationNode annotation, String key) {
        int index = valueIndex(annotation, key);
        return index < 0 ? null : annotation.values.get(index);
    }

    private static int valueIndex(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) return -1;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return i + 1;
        }
        return -1;
    }

    private static AnnotationNode annotation(List<AnnotationNode> visible, List<AnnotationNode> invisible, String desc) {
        if (visible != null) {
            for (AnnotationNode annotation : visible) if (desc.equals(annotation.desc)) return annotation;
        }
        if (invisible != null) {
            for (AnnotationNode annotation : invisible) if (desc.equals(annotation.desc)) return annotation;
        }
        return null;
    }
}
