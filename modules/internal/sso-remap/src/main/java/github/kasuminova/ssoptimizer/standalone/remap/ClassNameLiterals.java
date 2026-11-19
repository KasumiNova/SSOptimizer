package github.kasuminova.ssoptimizer.standalone.remap;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

final class ClassNameLiterals {
    private static final String STRING = "java/lang/String";
    private static final String NAMES = "github/kasuminova/ssoptimizer/standalone/remap/reflect/ReflectionNames";
    private static final String SELECTOR = "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/String;";
    private static final String MATCH = "(ZLjava/lang/Class;Ljava/lang/String;)Z";

    private record Case(VarInsnNode load, LdcInsnNode literal, MethodInsnNode equals, JumpInsnNode failure) { }
    private record StringSwitch(MethodInsnNode hash, List<Case> cases) { }

    private ClassNameLiterals() { }

    static void rewrite(ClassNode owner, MappingProjection mapping) {
        for (var method : List.copyOf(owner.methods)) {
            var instructions = method.instructions.toArray();
            boolean producesClassName = false;
            boolean consumesString = false;
            for (var insn : instructions) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                producesClassName |= classNameCall(call);
                consumesString |= equality(call) || hashSwitch(call);
            }
            if (!producesClassName || !consumesString) continue;
            Frame<SourceValue>[] frames = producers(owner, method);
            var entries = branchEntries(method);
            var switches = new ArrayList<StringSwitch>();
            for (var insn : instructions) {
                if (insn instanceof MethodInsnNode hash && hashSwitch(hash)) {
                    StringSwitch table = stringSwitch(hash, entries);
                    if (table == null) {
                        switches.clear();
                        break;
                    }
                    switches.add(table);
                }
            }
            Set<MethodInsnNode> guards = new HashSet<>();
            switches.forEach(table -> table.cases.forEach(entry -> guards.add(entry.equals)));
            // 对无法识别的哈希分支保留整段原始比较，避免同一个桶使用不同名称。
            if (switches.isEmpty() && java.util.Arrays.stream(instructions)
                    .anyMatch(insn -> insn instanceof MethodInsnNode call && hashSwitch(call))) continue;
            Map<MethodInsnNode, Integer> captures = new java.util.HashMap<>();
            for (int index = 0; index < instructions.length; index++) {
                if (!(instructions[index] instanceof MethodInsnNode call) || !equality(call)
                        || guards.contains(call) || frames[index] == null) continue;
                SourceValue left = stack(frames[index], 2), right = stack(frames[index], 1);
                MethodInsnNode producer = className(left);
                if (producer != null) rewriteComparison(method, call, producer, right, captures, entries, mapping);
                else if ((producer = className(right)) != null) rewriteComparison(method, call, producer, left, captures, entries, mapping);
            }
            for (var table : switches) {
                var frame = frames[methodIndex(instructions, table.hash)];
                if (frame == null) continue;
                var producer = className(stack(frame, 1));
                if (producer != null && sameBlock(producer, table.hash, entries)) {
                    rewriteSwitch(owner, method, table, producer, captures, mapping);
                }
            }
            if (!captures.isEmpty()) method.maxStack += 2;
        }
    }

    private static int methodIndex(AbstractInsnNode[] instructions, AbstractInsnNode target) {
        for (int i = 0; i < instructions.length; i++) if (instructions[i] == target) return i;
        throw new IllegalArgumentException("指令不在原方法中");
    }

    private static Frame<SourceValue>[] producers(ClassNode owner, MethodNode method) {
        try {
            return new Analyzer<>(new SourceInterpreter(ASM9) {
                private final AbstractInsnNode unknown = new InsnNode(NOP);

                @Override public SourceValue newValue(org.objectweb.asm.Type type) {
                    return type == org.objectweb.asm.Type.VOID_TYPE ? null : new SourceValue(type == null ? 1 : type.getSize(), unknown);
                }

                @Override public SourceValue copyOperation(AbstractInsnNode insn, SourceValue source) { return source; }
                @Override public SourceValue unaryOperation(AbstractInsnNode insn, SourceValue source) {
                    return insn.getOpcode() == CHECKCAST ? source : super.unaryOperation(insn, source);
                }
            }).analyze(owner.name, method);
        } catch (AnalyzerException e) {
            throw new IllegalArgumentException("Cannot analyze class-name comparisons in " + owner.name + "." + method.name + method.desc, e);
        }
    }

    private static SourceValue stack(Frame<SourceValue> frame, int depth) {
        return frame.getStack(frame.getStackSize() - depth);
    }

    private static MethodInsnNode className(SourceValue source) {
        return source.insns.size() == 1 && source.insns.iterator().next() instanceof MethodInsnNode call && classNameCall(call)
                ? call : null;
    }

    private static boolean classNameCall(MethodInsnNode call) {
        return call.getOpcode() == INVOKEVIRTUAL && call.owner.equals("java/lang/Class") && call.name.equals("getName")
                && call.desc.equals("()Ljava/lang/String;");
    }

    private static boolean hashSwitch(MethodInsnNode call) {
        var next = real(call.getNext());
        return stringCall(call, "hashCode", "()I") && (next instanceof LookupSwitchInsnNode || next instanceof TableSwitchInsnNode);
    }

    private static boolean equality(MethodInsnNode call) {
        if (stringCall(call, "equals", "(Ljava/lang/Object;)Z")) return true;
        if (call.getOpcode() != INVOKESTATIC || !call.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)Z")) return false;
        return call.owner.equals("java/util/Objects") && call.name.equals("equals")
                || call.owner.equals("kotlin/jvm/internal/Intrinsics") && call.name.equals("areEqual");
    }

    private static void rewriteComparison(MethodNode method, MethodInsnNode call, MethodInsnNode producer, SourceValue source,
                                          Map<MethodInsnNode, Integer> captures, Map<AbstractInsnNode, Set<AbstractInsnNode>> entries,
                                          MappingProjection mapping) {
        if (source.insns.size() != 1 || !(source.insns.iterator().next() instanceof LdcInsnNode literal)
                || !(literal.cst instanceof String name) || name.equals(binaryName(name, mapping))
                || !sameBlock(producer, call, entries)) return;
        int slot = capture(method, producer, captures);
        var after = new InsnList();
        after.add(new VarInsnNode(ALOAD, slot));
        after.add(new LdcInsnNode(name));
        after.add(new MethodInsnNode(INVOKESTATIC, NAMES, "classNameMatches", MATCH, false));
        method.instructions.insert(call, after);
    }

    private static int capture(MethodNode method, MethodInsnNode producer, Map<MethodInsnNode, Integer> captures) {
        return captures.computeIfAbsent(producer, call -> {
            int slot = method.maxLocals++;
            var save = new InsnList();
            save.add(new InsnNode(DUP));
            save.add(new VarInsnNode(ASTORE, slot));
            method.instructions.insertBefore(call, save);
            return slot;
        });
    }

    static String binaryName(String name, MappingProjection mapping) {
        int start = 0;
        while (start < name.length() && name.charAt(start) == '[') start++;
        if (start > 0 && (start > 255 || start + 2 >= name.length() || name.charAt(start++) != 'L' || !name.endsWith(";"))) return name;
        int end = start == 0 ? name.length() : name.length() - 1;
        String element = name.substring(start, end);
        if (element.indexOf('/') >= 0) return name;
        String internal = element.replace('.', '/');
        String mapped = mapping.mapClass(internal);
        return mapped.equals(internal) ? name : name.substring(0, start) + mapped.replace('/', '.') + name.substring(end);
    }

    private static StringSwitch stringSwitch(MethodInsnNode hash, Map<AbstractInsnNode, Set<AbstractInsnNode>> entries) {
        if (!(realBefore(hash.getPrevious()) instanceof VarInsnNode source) || source.getOpcode() != ALOAD) return null;
        var split = real(hash.getNext());
        LabelNode defaultLabel;
        List<LabelNode> labels;
        List<Integer> keys;
        if (split instanceof LookupSwitchInsnNode lookup) {
            defaultLabel = lookup.dflt;
            labels = lookup.labels;
            keys = lookup.keys;
        } else if (split instanceof TableSwitchInsnNode table) {
            defaultLabel = table.dflt;
            labels = table.labels;
            keys = java.util.stream.IntStream.range(0, labels.size()).map(i -> table.min + i).boxed().toList();
        } else return null;
        var cases = new ArrayList<Case>();
        Set<AbstractInsnNode> loads = new HashSet<>();
        for (int i = 0; i < labels.size(); i++) {
            LabelNode label = labels.get(i);
            AbstractInsnNode predecessor = split;
            while (label != defaultLabel) {
                if (!(real(label) instanceof VarInsnNode load) || load.getOpcode() != ALOAD || load.var != source.var
                        || !loads.add(load) || !entries.getOrDefault(load, Set.of()).equals(Set.of(predecessor))
                        || fallsThrough(realBefore(label.getPrevious()))
                        || !(real(load.getNext()) instanceof LdcInsnNode literal) || !(literal.cst instanceof String text)
                        || text.hashCode() != keys.get(i) || !(real(literal.getNext()) instanceof MethodInsnNode equals)
                        || !stringCall(equals, "equals", "(Ljava/lang/Object;)Z")
                        || !(real(equals.getNext()) instanceof JumpInsnNode failure) || failure.getOpcode() != IFEQ
                        || entries.containsKey(literal) || entries.containsKey(equals) || entries.containsKey(failure)) return null;
                cases.add(new Case(load, literal, equals, failure));
                predecessor = failure;
                label = failure.label;
            }
        }
        return new StringSwitch(hash, cases);
    }

    private static void rewriteSwitch(ClassNode owner, MethodNode method, StringSwitch table, MethodInsnNode producer,
                                      Map<MethodInsnNode, Integer> captures, MappingProjection mapping) {
        var literals = table.cases.stream().map(entry -> (String) entry.literal.cst).distinct().toList();
        var aliases = literals.stream().filter(name -> !name.equals(binaryName(name, mapping))).toList();
        if (aliases.isEmpty()) return;
        int slot = capture(method, producer, captures);
        int selector = method.maxLocals;
        extendFrames(method, selector);
        var before = new InsnList();
        before.add(new VarInsnNode(ALOAD, slot));
        var helper = switchSelector(owner, literals, aliases);
        before.add(new MethodInsnNode(INVOKESTATIC, owner.name, helper.name, SELECTOR, (owner.access & ACC_INTERFACE) != 0));
        before.add(new InsnNode(DUP));
        before.add(new VarInsnNode(ASTORE, selector));
        method.instructions.insertBefore(table.hash, before);
        table.cases.forEach(entry -> entry.load.var = selector);
    }

    private static void extendFrames(MethodNode method, int slot) {
        for (var insn : method.instructions) {
            if (!(insn instanceof FrameNode frame)) continue;
            if (frame.local == null) frame.local = new ArrayList<>();
            int size = 0;
            for (Object local : frame.local) size += LONG.equals(local) || DOUBLE.equals(local) ? 2 : 1;
            while (size++ < slot) frame.local.add(TOP);
            frame.local.add(STRING);
        }
        var initialize = new InsnList();
        initialize.add(new InsnNode(ACONST_NULL));
        initialize.add(new VarInsnNode(ASTORE, slot));
        method.instructions.insert(initialize);
        method.maxLocals = slot + 1;
    }

    private static MethodNode switchSelector(ClassNode owner, List<String> literals, List<String> aliases) {
        var names = new HashSet<String>();
        owner.methods.forEach(method -> names.add(method.name));
        int suffix = 0;
        String name;
        do name = "$sso$stringSwitch$" + suffix++; while (names.contains(name));
        boolean itf = (owner.access & ACC_INTERFACE) != 0;
        int access = itf && (owner.version & 0xffff) < V9 ? ACC_PUBLIC : ACC_PRIVATE;
        var helper = new MethodNode(access | ACC_STATIC | ACC_SYNTHETIC, name, SELECTOR, null, null);
        for (String literal : literals) {
            helper.instructions.add(new LdcInsnNode(literal));
            helper.instructions.add(new VarInsnNode(ALOAD, 0));
            helper.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, STRING, "equals", "(Ljava/lang/Object;)Z", false));
            returnOnMatch(helper, literal);
        }
        for (String alias : aliases) {
            helper.instructions.add(new InsnNode(ICONST_0));
            helper.instructions.add(new VarInsnNode(ALOAD, 1));
            helper.instructions.add(new LdcInsnNode(alias));
            helper.instructions.add(new MethodInsnNode(INVOKESTATIC, NAMES, "classNameMatches", MATCH, false));
            returnOnMatch(helper, alias);
        }
        helper.instructions.add(new VarInsnNode(ALOAD, 0));
        helper.instructions.add(new InsnNode(ARETURN));
        helper.maxStack = 3;
        helper.maxLocals = 2;
        owner.methods.add(helper);
        return helper;
    }

    private static void returnOnMatch(MethodNode helper, String literal) {
        var next = new LabelNode();
        helper.instructions.add(new JumpInsnNode(IFEQ, next));
        helper.instructions.add(new LdcInsnNode(literal));
        helper.instructions.add(new InsnNode(ARETURN));
        helper.instructions.add(next);
        helper.instructions.add(new FrameNode(F_NEW, 2, new Object[]{STRING, "java/lang/Class"}, 0, null));
    }

    private static Map<AbstractInsnNode, Set<AbstractInsnNode>> branchEntries(MethodNode method) {
        Map<AbstractInsnNode, Set<AbstractInsnNode>> entries = new java.util.HashMap<>();
        for (var insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump) addEntry(entries, jump.label, jump);
            else if (insn instanceof LookupSwitchInsnNode lookup) {
                addEntry(entries, lookup.dflt, lookup);
                lookup.labels.forEach(label -> addEntry(entries, label, lookup));
            } else if (insn instanceof TableSwitchInsnNode table) {
                addEntry(entries, table.dflt, table);
                table.labels.forEach(label -> addEntry(entries, label, table));
            }
        }
        method.tryCatchBlocks.forEach(block -> addEntry(entries, block.handler, block.handler));
        return entries;
    }

    private static void addEntry(Map<AbstractInsnNode, Set<AbstractInsnNode>> entries, LabelNode label, AbstractInsnNode source) {
        entries.computeIfAbsent(real(label), ignored -> new HashSet<>()).add(source);
    }

    private static boolean sameBlock(AbstractInsnNode start, AbstractInsnNode end, Map<AbstractInsnNode, Set<AbstractInsnNode>> entries) {
        for (var insn = start.getNext(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FrameNode || entries.containsKey(insn)) return false;
            if (insn == end) return true;
            if (insn instanceof JumpInsnNode || !fallsThrough(insn)) return false;
        }
        return false;
    }

    private static boolean fallsThrough(AbstractInsnNode insn) {
        return insn != null && !(insn instanceof LookupSwitchInsnNode || insn instanceof TableSwitchInsnNode)
                && switch (insn.getOpcode()) {
                    case GOTO, RET, ATHROW, IRETURN, LRETURN, FRETURN, DRETURN, ARETURN, RETURN -> false;
                    default -> true;
                };
    }

    private static boolean stringCall(MethodInsnNode call, String method, String descriptor) {
        return call.getOpcode() == INVOKEVIRTUAL && call.owner.equals(STRING) && call.name.equals(method) && call.desc.equals(descriptor);
    }

    private static AbstractInsnNode real(AbstractInsnNode node) {
        while (node != null && node.getOpcode() < 0) node = node.getNext();
        return node;
    }

    private static AbstractInsnNode realBefore(AbstractInsnNode node) {
        while (node != null && node.getOpcode() < 0) node = node.getPrevious();
        return node;
    }
}
