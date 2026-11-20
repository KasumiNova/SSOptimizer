package github.kasuminova.ssoptimizer.reobf;

import net.fabricmc.tinyremapper.extension.mixin.soft.data.MemberInfo;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class MixinSymbols {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String DESC = "Lorg/spongepowered/asm/mixin/injection/Desc;";

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

    static int encodeSelectors(ClassNode mixin, GameClasses game) throws IOException {
        List<String> targets = targets(mixin);
        if (targets.isEmpty()) return 0;
        int changed = 0;
        for (var method : mixin.methods) {
            changed += encodeInjectors(method.visibleAnnotations, targets, game);
            changed += encodeInjectors(method.invisibleAnnotations, targets, game);
        }
        return changed;
    }

    private static int encodeInjectors(List<AnnotationNode> annotations, List<String> targets, GameClasses game) throws IOException {
        if (annotations == null) return 0;
        int changed = 0;
        for (AnnotationNode annotation : annotations) {
            if (!annotation.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                    && !annotation.desc.startsWith("Lcom/llamalad7/mixinextras/")) continue;
            if (value(annotation, "method") instanceof List<?> selectors) {
                var legacy = new ArrayList<String>();
                var structured = new ArrayList<AnnotationNode>();
                for (Object raw : selectors) {
                    String selector = (String) raw;
                    MemberInfo member = restrictedSelector(selector);
                    if (member == null) {
                        legacy.add(selector);
                    } else {
                        encodeMethods(member, targets, game, structured);
                        changed++;
                    }
                }
                if (!structured.isEmpty()) {
                    if (value(annotation, "target") instanceof List<?> existing) {
                        for (Object target : existing) structured.add((AnnotationNode) target);
                    }
                    setValue(annotation, "method", legacy);
                    setValue(annotation, "target", structured);
                }
            }
            changed += encodePoints(annotation.values);
        }
        return changed;
    }

    private static void encodeMethods(MemberInfo member, List<String> targets, GameClasses game, List<AnnotationNode> result) throws IOException {
        if (!member.getDesc().isEmpty()) {
            result.add(descriptor(member, member.getOwner(), member.getDesc()));
            return;
        }
        List<String> owners = member.getOwner().isEmpty() ? targets : List.of(member.getOwner());
        boolean any = "*".equals(member.getQuantifier());
        int size = result.size();
        for (String owner : owners) {
            var descriptors = game.methodDescriptors(owner, member.getName());
            if (descriptors.size() > 1 && !member.getQuantifier().isEmpty() && !any) {
                throw new IllegalArgumentException("A restricted method selector with a match limit needs a descriptor: " + member);
            }
            for (String desc : descriptors) {
                result.add(descriptor(member, owner, desc));
                if (!any) break;
            }
        }
        if (result.size() == size) {
            throw new IllegalArgumentException("Cannot determine the descriptor of restricted method selector " + member + " on " + targets);
        }
    }

    private static int encodePoints(Object value) {
        if (value instanceof List<?> values) {
            int changed = 0;
            for (Object item : values) changed += encodePoints(item);
            return changed;
        }
        if (!(value instanceof AnnotationNode annotation)) return 0;
        if (!AT.equals(annotation.desc)) return encodePoints(annotation.values);
        if (!(value(annotation, "target") instanceof String selector) || "NEW".equals(value(annotation, "value"))) return 0;
        MemberInfo member = restrictedSelector(selector);
        if (member == null) return 0;
        if (!member.isFullyQualified()) {
            throw new IllegalArgumentException("A restricted @At selector needs an owner, name and descriptor: " + selector);
        }
        setValue(annotation, "target", "");
        setValue(annotation, "desc", descriptor(member, member.getOwner(), member.getDesc()));
        return 1;
    }

    private static MemberInfo restrictedSelector(String selector) {
        if (selector.contains("->")) return null;
        MemberInfo member = MemberInfo.parse(selector);
        if (member == null) return null;
        String owner = member.getOwner();
        String name = member.getName();
        String desc = member.getDesc();
        // Mixin 0.8.7 validates these components without Unicode word matching.
        return !owner.isEmpty() && !owner.matches("[\\w\\p{Sc}/]+")
                || !name.isEmpty() && !name.matches("<?[\\w\\p{Sc}]+>?")
                || !desc.isEmpty() && !desc.matches("(\\([\\w\\p{Sc}\\[/;]*\\))?\\[*[\\w\\p{Sc}/;]+") ? member : null;
    }

    private static AnnotationNode descriptor(MemberInfo member, String owner, String desc) {
        var annotation = new AnnotationNode(DESC);
        annotation.visit("value", member.getName());
        if (!owner.isEmpty()) annotation.visit("owner", Type.getObjectType(owner));
        if (desc.startsWith("(")) {
            annotation.visit("ret", Type.getReturnType(desc));
            annotation.values.add("args");
            annotation.values.add(List.of(Type.getArgumentTypes(desc)));
        } else {
            annotation.visit("ret", Type.getType(desc));
        }
        setBounds(annotation, member.getQuantifier());
        return annotation;
    }

    private static void setBounds(AnnotationNode desc, String quantifier) {
        if (quantifier.isEmpty()) return;
        if ("*".equals(quantifier) || "+".equals(quantifier)) {
            desc.visit("min", "+".equals(quantifier) ? 1 : 0);
            desc.visit("max", Integer.MAX_VALUE);
            return;
        }
        var bounds = quantifier.substring(1, quantifier.length() - 1).split(",", -1);
        int min = bounds[0].isEmpty() ? 0 : Integer.parseInt(bounds[0]);
        int max = bounds.length == 1 ? min : bounds[1].isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(bounds[1]);
        if (min < 0 || max < 1 || max < min) throw new IllegalArgumentException("Invalid selector quantifier: " + quantifier);
        desc.visit("min", min);
        desc.visit("max", max);
    }

    private static void setValue(AnnotationNode annotation, String key, Object value) {
        int index = valueIndex(annotation, key);
        if (index < 0) {
            annotation.visit(key, value);
        } else {
            annotation.values.set(index, value);
        }
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
