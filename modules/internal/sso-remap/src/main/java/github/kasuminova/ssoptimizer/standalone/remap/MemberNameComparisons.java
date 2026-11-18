package github.kasuminova.ssoptimizer.standalone.remap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.util.*;

/** 仅扩展已证明来源的成员名比较；原成员读取和 equals 调用仍按原顺序执行。 */
final class MemberNameComparisons {
    private static final System.Logger LOGGER = System.getLogger(MemberNameComparisons.class.getName());
    private static final String HANDLE = "java/lang/invoke/MethodHandle";
    private static final String BRIDGE = "github/kasuminova/ssoptimizer/standalone/remap/reflect/ReflectionNames";
    private MemberNameComparisons() {}

    static void rewrite(final ClassNode owner) {
        boolean compares = false;
        boolean readsName = false;
        final Set<MethodNode> relevant = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                boolean equals = call.owner.equals("java/lang/String") && call.name.equals("equals") && call.desc.equals("(Ljava/lang/Object;)Z");
                boolean name = call.name.equals("getName") || call.owner.equals(HANDLE);
                boolean init = call.owner.equals("java/lang/invoke/MethodHandles$Lookup") && call.name.equals("findVirtual");
                boolean wrapper = call.owner.equals(owner.name) && nameDescriptor(call.desc);
                compares |= equals;
                readsName |= name;
                if (equals || name || init || wrapper) relevant.add(method);
            }
        }
        if (!compares || !readsName) return;
        final Map<MethodNode, Flow> flows = new IdentityHashMap<>();
        for (MethodNode method : relevant) {
            try { flows.put(method, new Flow(owner.name, method)); }
            catch (AnalyzerException unsupported) {
                LOGGER.log(System.Logger.Level.WARNING, "Keeping member-name comparisons unchanged in "
                        + owner.name + "." + method.name + method.desc, unsupported);
                return;
            }
        }
        final Set<String> handles = handles(owner, flows);
        final Set<String> helpers = new HashSet<>();
        boolean changed;
        do {
            changed = false;
            for (var entry : flows.entrySet()) {
                MethodNode method = entry.getKey();
                if ((method.access & Opcodes.ACC_STATIC) == 0 || !nameDescriptor(method.desc)) continue;
                boolean found = false;
                boolean valid = true;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction.getOpcode() != Opcodes.ARETURN) continue;
                    SourceValue value = entry.getValue().top(instruction, 0);
                    if (value == null) continue;
                    for (AbstractInsnNode source : value.insns) {
                        if (source.getOpcode() == Opcodes.ACONST_NULL) continue;
                        if (!(source instanceof MethodInsnNode call) || !nameCall(owner.name, call, handles, helpers, entry.getValue())) {
                            valid = false; break;
                        }
                        SourceValue argument = entry.getValue().top(call, 0);
                        if (!parameter(argument, 0)) { valid = false; break; }
                        found = true;
                    }
                    if (value.insns.isEmpty()) valid = false;
                }
                if (valid && found) changed |= helpers.add(method.name + method.desc);
            }
        } while (changed);
        for (var entry : flows.entrySet()) rewriteMethod(owner.name, entry.getKey(), entry.getValue(), handles, helpers);
    }

    private static void rewriteMethod(String owner, MethodNode method, Flow flow, Set<String> handles, Set<String> helpers) {
        int scratch = -1;
        final Map<MethodInsnNode, Integer> captured = new IdentityHashMap<>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode equals) || equals.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !equals.owner.equals("java/lang/String") || !equals.name.equals("equals")
                    || !equals.desc.equals("(Ljava/lang/Object;)Z") || extended(equals)) continue;
            MethodInsnNode producer = producer(owner, flow.top(equals, 1), handles, helpers, flow);
            boolean nameIsReceiver = producer != null;
            if (producer == null) producer = producer(owner, flow.top(equals, 0), handles, helpers, flow);
            if (producer == null || !linear(method, producer, equals)) continue;
            if (scratch < 0) {
                scratch = method.maxLocals;
                method.maxLocals += 3;
                method.maxStack += 4;
            }
            Integer member = captured.get(producer);
            if (member == null) {
                member = method.maxLocals++;
                captured.put(producer, member);
                InsnList capture = new InsnList();
                capture.add(new InsnNode(Opcodes.DUP));
                capture.add(new VarInsnNode(Opcodes.ASTORE, member));
                method.instructions.insertBefore(producer, capture);
            }
            int receiver = scratch;
            int argument = scratch + 1;
            InsnList save = new InsnList();
            save.add(new VarInsnNode(Opcodes.ASTORE, argument));
            save.add(new InsnNode(Opcodes.DUP));
            save.add(new VarInsnNode(Opcodes.ASTORE, receiver));
            save.add(new VarInsnNode(Opcodes.ALOAD, argument));
            method.instructions.insertBefore(equals, save);
            int result = scratch + 2;
            InsnList extension = new InsnList();
            extension.add(new VarInsnNode(Opcodes.ISTORE, result));
            extension.add(new VarInsnNode(Opcodes.ALOAD, member));
            extension.add(new VarInsnNode(Opcodes.ALOAD, nameIsReceiver ? argument : receiver));
            extension.add(new VarInsnNode(Opcodes.ALOAD, nameIsReceiver ? receiver : argument));
            extension.add(new VarInsnNode(Opcodes.ILOAD, result));
            extension.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "comparison",
                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Z)Z", false));
            method.instructions.insert(equals, extension);
        }
    }

    private static boolean extended(MethodInsnNode equals) {
        AbstractInsnNode cursor = equals.getNext();
        for (int opcode : new int[]{Opcodes.ISTORE, Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.ILOAD}) {
            if (cursor == null || cursor.getOpcode() != opcode) return false;
            cursor = cursor.getNext();
        }
        return cursor instanceof MethodInsnNode call && call.owner.equals(BRIDGE) && call.name.equals("comparison")
                && call.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Z)Z");
    }

    private static boolean linear(MethodNode method, AbstractInsnNode from, AbstractInsnNode to) {
        Set<LabelNode> targets = new HashSet<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof JumpInsnNode jump) targets.add(jump.label);
            if (instruction instanceof TableSwitchInsnNode table) { targets.add(table.dflt); targets.addAll(table.labels); }
            if (instruction instanceof LookupSwitchInsnNode table) { targets.add(table.dflt); targets.addAll(table.labels); }
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks) targets.add(block.handler);
        for (AbstractInsnNode instruction = from.getNext(); instruction != null; instruction = instruction.getNext()) {
            if (instruction == to) return true;
            if (targets.contains(instruction) || instruction instanceof FrameNode || instruction instanceof JumpInsnNode || instruction instanceof TableSwitchInsnNode
                    || instruction instanceof LookupSwitchInsnNode || instruction.getOpcode() == Opcodes.RET
                    || instruction.getOpcode() == Opcodes.ATHROW || (instruction.getOpcode() >= Opcodes.IRETURN && instruction.getOpcode() <= Opcodes.RETURN)) return false;
        }
        return false;
    }

    private static MethodInsnNode producer(String owner, SourceValue value, Set<String> handles, Set<String> helpers, Flow flow) {
        if (value == null || value.insns.size() != 1) return null;
        AbstractInsnNode source = value.insns.iterator().next();
        return source instanceof MethodInsnNode call && nameCall(owner, call, handles, helpers, flow) ? call : null;
    }

    private static boolean nameCall(String owner, MethodInsnNode call, Set<String> handles, Set<String> helpers, Flow flow) {
        if (call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(owner)) return helpers.contains(call.name + call.desc);
        if (call.name.equals("getName") && call.desc.equals("()Ljava/lang/String;")
                && Set.of("java/lang/reflect/Method", "java/lang/reflect/Field", "java/lang/reflect/Member").contains(call.owner)
                && (call.getOpcode() == Opcodes.INVOKEVIRTUAL || call.getOpcode() == Opcodes.INVOKEINTERFACE)) return true;
        if (!call.owner.equals(HANDLE) || !(call.name.equals("invoke") || call.name.equals("invokeExact"))
                || call.getOpcode() != Opcodes.INVOKEVIRTUAL || !nameDescriptor(call.desc)) return false;
        SourceValue handle = flow.top(call, 1);
        if (handle == null || handle.insns.size() != 1) return false;
        AbstractInsnNode source = handle.insns.iterator().next();
        return source instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                && field.owner.equals(owner) && handles.contains(field.name);
    }

    private static Set<String> handles(ClassNode owner, Map<MethodNode, Flow> flows) {
        Set<String> candidates = new HashSet<>();
        for (FieldNode field : owner.fields) {
            if (field.desc.equals("Ljava/lang/invoke/MethodHandle;")
                    && (field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) candidates.add(field.name);
        }
        Set<String> valid = new HashSet<>();
        Set<String> invalid = new HashSet<>();
        for (MethodNode method : owner.methods) {
            Flow flow = flows.get(method);
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                        || !field.owner.equals(owner.name) || !candidates.contains(field.name)) continue;
                SourceValue value = flow == null ? null : flow.top(field, 0);
                boolean found = false;
                boolean good = method.name.equals("<clinit>") && value != null && !value.insns.isEmpty();
                if (value != null) for (AbstractInsnNode source : value.insns) {
                    if (source.getOpcode() == Opcodes.ACONST_NULL) continue;
                    if (source instanceof MethodInsnNode call && getNameHandle(call, flow)) found = true;
                    else good = false;
                }
                if (good && found) valid.add(field.name); else invalid.add(field.name);
            }
        }
        valid.removeAll(invalid);
        return valid;
    }

    private static boolean getNameHandle(MethodInsnNode call, Flow flow) {
        if (!call.owner.equals("java/lang/invoke/MethodHandles$Lookup") || !call.name.equals("findVirtual")
                || !call.desc.equals("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;")) return false;
        if (!literal(flow.top(call, 1), "getName")) return false;
        SourceValue type = flow.top(call, 2);
        if (type == null || type.insns.size() != 1) return false;
        AbstractInsnNode source = type.insns.iterator().next();
        boolean member = source instanceof LdcInsnNode ldc && ldc.cst instanceof Type t && memberClass(t.getClassName());
        if (source instanceof MethodInsnNode lookup && lookup.owner.equals("java/lang/Class") && lookup.name.equals("forName")) {
            int args = Type.getArgumentTypes(lookup.desc).length;
            SourceValue name = flow.top(lookup, args - 1);
            member = name != null && name.insns.size() == 1 && name.insns.iterator().next() instanceof LdcInsnNode ldc
                    && ldc.cst instanceof String text && memberClass(text);
        }
        SourceValue signature = flow.top(call, 0);
        if (!member || signature == null || signature.insns.size() != 1) return false;
        return signature.insns.iterator().next() instanceof MethodInsnNode factory
                && factory.owner.equals("java/lang/invoke/MethodType") && factory.name.equals("methodType")
                && factory.desc.equals("(Ljava/lang/Class;)Ljava/lang/invoke/MethodType;")
                && literal(flow.top(factory, 0), Type.getType(String.class));
    }

    private static boolean nameDescriptor(String descriptor) {
        Type[] arguments = Type.getArgumentTypes(descriptor);
        return arguments.length == 1 && arguments[0].getSort() == Type.OBJECT
                && Type.getReturnType(descriptor).equals(Type.getType(String.class));
    }

    private static boolean memberClass(String name) {
        return name.equals("java.lang.reflect.Method") || name.equals("java.lang.reflect.Field") || name.equals("java.lang.reflect.Member");
    }

    private static boolean literal(SourceValue value, Object expected) {
        return value != null && value.insns.size() == 1 && value.insns.iterator().next() instanceof LdcInsnNode ldc && expected.equals(ldc.cst);
    }

    private static boolean parameter(SourceValue value, int index) {
        return value != null && value.insns.size() == 1 && value.insns.iterator().next() instanceof VarInsnNode var
                && var.getOpcode() == Opcodes.ALOAD && var.var == index;
    }

    private static final class Flow {
        private final Map<AbstractInsnNode, Frame<SourceValue>> frames = new IdentityHashMap<>();
        Flow(String owner, MethodNode method) throws AnalyzerException {
            Frame<SourceValue>[] analyzed = new Analyzer<>(new Sources()).analyze(owner, method);
            for (int i = 0; i < analyzed.length; i++) frames.put(method.instructions.get(i), analyzed[i]);
        }
        SourceValue top(AbstractInsnNode instruction, int depth) {
            Frame<SourceValue> frame = frames.get(instruction);
            return frame == null || frame.getStackSize() <= depth ? null : frame.getStack(frame.getStackSize() - 1 - depth);
        }
    }

    private static final class Sources extends SourceInterpreter {
        Sources() { super(Opcodes.ASM9); }
        @Override public SourceValue newParameterValue(boolean instance, int local, Type type) {
            return new SourceValue(type.getSize(), new VarInsnNode(Opcodes.ALOAD, local));
        }
        @Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) { return value; }
        @Override public SourceValue unaryOperation(AbstractInsnNode instruction, SourceValue value) {
            return instruction.getOpcode() == Opcodes.CHECKCAST ? value : super.unaryOperation(instruction, value);
        }
    }
}
