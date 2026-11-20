package github.kasuminova.ssoptimizer.standalone.obf;

import java.util.ArrayList;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

final class MemberArguments {
    private MemberArguments() {}

    private record Slot(int trailing, boolean method) {}
    private record Call(MethodInsnNode instruction, Slot slot, int index) {}

    static boolean rewrite(String owner, MethodNode method, GameMembers members) {
        var sites = new ArrayList<Call>();
        boolean possible = false;
        int index = 0;
        for (var insn : method.instructions) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s && ObfClasses.invalid(s, true)) possible = true;
            if (insn instanceof MethodInsnNode call) {
                Slot slot = slot(call);
                if (slot != null) sites.add(new Call(call, slot, index));
            }
            index++;
        }
        if (!possible || sites.isEmpty()) return false;
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter(Opcodes.ASM9) {
                @Override public SourceValue copyOperation(AbstractInsnNode insn, SourceValue value) { return value; }
                @Override public SourceValue merge(SourceValue left, SourceValue right) {
                    if (left.insns.isEmpty() || right.insns.isEmpty()) return new SourceValue(Math.min(left.getSize(), right.getSize()));
                    return super.merge(left, right);
                }
            }).analyze(owner, method);
        } catch (AnalyzerException e) {
            throw new IllegalArgumentException("成员参数分析失败 " + owner + '.' + method.name + method.desc, e);
        }
        boolean changed = false;
        for (var site : sites) {
            var frame = frames[site.index()];
            if (frame == null) continue;
            int trailing = site.slot().trailing();
            var value = frame.getStack(frame.getStackSize() - trailing - 1);
            if (value.insns.size() != 1 || !(value.insns.iterator().next() instanceof LdcInsnNode literal) || !(literal.cst instanceof String text)) continue;
            String fixed = ObfClasses.memberName(text, site.slot().method());
            if (fixed.equals(text)) continue;
            var call = site.instruction();
            int ownerIndex = frame.getStackSize() - trailing - 2;
            var source = frame.getStack(ownerIndex);
            if (source.insns.size() != 1 || !(source.insns.iterator().next() instanceof LdcInsnNode typeLiteral)
                    || !(typeLiteral.cst instanceof Type type) || type.getSort() != Type.OBJECT) continue;
            boolean classLookup = call.owner.equals("java/lang/Class");
            boolean declared = classLookup && call.name.startsWith("getDeclared");
            if (!members.has(type.getInternalName(), text, site.slot().method(), declared, classLookup && !declared)) continue;
            var patch = new InsnList();
            if (trailing == 2) {
                int scratch = method.maxLocals;
                method.maxLocals += 2;
                patch.add(new VarInsnNode(Opcodes.ASTORE, scratch + 1));
                patch.add(new VarInsnNode(Opcodes.ASTORE, scratch));
                patch.add(new InsnNode(Opcodes.POP));
                patch.add(new LdcInsnNode(fixed));
                patch.add(new VarInsnNode(Opcodes.ALOAD, scratch));
                patch.add(new VarInsnNode(Opcodes.ALOAD, scratch + 1));
            } else {
                if (trailing == 1) patch.add(new InsnNode(Opcodes.SWAP));
                patch.add(new InsnNode(Opcodes.POP));
                patch.add(new LdcInsnNode(fixed));
                if (trailing == 1) patch.add(new InsnNode(Opcodes.SWAP));
            }
            method.instructions.insertBefore(site.instruction(), patch);
            changed = true;
        }
        return changed;
    }

    private static Slot slot(MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKEVIRTUAL) return null;
        return switch (call.owner) {
            case "java/lang/Class" -> switch (call.name + call.desc) {
                case "getField(Ljava/lang/String;)Ljava/lang/reflect/Field;", "getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;" -> new Slot(0, false);
                case "getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", "getDeclaredMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;" -> new Slot(1, true);
                default -> null;
            };
            case "java/lang/invoke/MethodHandles$Lookup" -> switch (call.name + call.desc) {
                case "findGetter(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", "findSetter(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", "findStaticGetter(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", "findStaticSetter(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", "findVarHandle(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;", "findStaticVarHandle(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;" -> new Slot(1, false);
                case "findSpecial(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;" -> new Slot(2, true);
                case "findVirtual(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", "findStatic(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;" -> new Slot(1, true);
                default -> null;
            };
            case "com/thoughtworks/xstream/XStream" -> switch (call.name + call.desc) {
                case "aliasField(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/String;)V", "useAttributeFor(Ljava/lang/Class;Ljava/lang/String;)V", "omitField(Ljava/lang/Class;Ljava/lang/String;)V", "addImplicitCollection(Ljava/lang/Class;Ljava/lang/String;)V" -> new Slot(0, false);
                case "addImplicitCollection(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Class;)V", "addImplicitArray(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Class;)V" -> new Slot(2, false);
                case "aliasAttribute(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)V", "addImplicitCollection(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)V" -> new Slot(1, false);
                default -> null;
            };
            default -> null;
        };
    }
}
