package github.kasuminova.ssoptimizer.standalone.remap;

import org.objectweb.asm.Type;
import java.util.List;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.objectweb.asm.Opcodes.*;

final class ReflectionLookups {
    private static final String NAMES = "github/kasuminova/ssoptimizer/standalone/remap/reflect/ReflectionNames";
    private static final String C = "Ljava/lang/Class;", S = "Ljava/lang/String;", T = "Ljava/lang/invoke/MethodType;";
    private static final String H = "Ljava/lang/invoke/MethodHandle;", V = "Ljava/lang/invoke/VarHandle;";

    private enum Name {
        CLASS("className", "(" + S + ")" + S), METHOD("methodName", "(" + C + S + T + ")" + S),
        PARAMETERS("methodName", "(" + C + S + "[" + C + ")" + S), DECLARED_PARAMETERS("declaredMethodName", "(" + C + S + "[" + C + ")" + S),
        TYPED_FIELD("fieldName", "(" + C + S + C + ")" + S), FIELD("fieldName", "(" + C + S + ")" + S),
        DECLARED_FIELD("declaredFieldName", "(" + C + S + ")" + S);

        final String helper, descriptor;
        Name(String helper, String descriptor) { this.helper = helper; this.descriptor = descriptor; }
    }

    private ReflectionLookups() {}

    static void rewrite(ClassNode node) {
        var classes = new ClassLookups(node);
        for (var method : List.copyOf(node.methods)) {
            int scratch = method.maxLocals;
            int extraStack = 0;
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                Name name = lookup(call);
                if (name == null) continue;
                if (name == Name.CLASS) {
                    classes.replace(call);
                    continue;
                }
                Type[] args = Type.getArgumentTypes(call.desc);
                int index = 0;
                while (!args[index].getDescriptor().equals(S)) index++;
                InsnList before = new InsnList();
                // All arguments following the name in these JDK signatures occupy one slot.
                for (int i = args.length - 1; i > index; i--) {
                    before.add(new VarInsnNode(args[i].getOpcode(ISTORE), scratch + i - index - 1));
                }
                int used = args.length - index - 1;
                before.add(new VarInsnNode(ASTORE, scratch + used++));
                before.add(new InsnNode(DUP));
                before.add(new VarInsnNode(ALOAD, scratch + used - 1));
                if (name != Name.FIELD && name != Name.DECLARED_FIELD) before.add(new VarInsnNode(ALOAD, scratch));
                extraStack = 1;
                before.add(new MethodInsnNode(INVOKESTATIC, NAMES, name.helper, name.descriptor, false));
                for (int i = index + 1; i < args.length; i++) {
                    before.add(new VarInsnNode(args[i].getOpcode(ILOAD), scratch + i - index - 1));
                }
                method.instructions.insertBefore(call, before);
                method.maxLocals = Math.max(method.maxLocals, scratch + used);
            }
            method.maxStack += extraStack;
        }
    }

    private static Name lookup(MethodInsnNode call) {
        if (call.owner.equals("java/lang/Class")) {
            if (call.getOpcode() == INVOKESTATIC && call.name.equals("forName")) {
                return switch (call.desc) {
                    case "(" + S + ")" + C, "(" + S + "ZLjava/lang/ClassLoader;)" + C,
                            "(Ljava/lang/Module;" + S + ")" + C -> Name.CLASS;
                    default -> null;
                };
            }
            if (call.getOpcode() != INVOKEVIRTUAL) return null;
            return switch (call.name) {
                case "getMethod", "getDeclaredMethod" -> call.desc.equals("(" + S + "[" + C + ")Ljava/lang/reflect/Method;")
                        ? (call.name.equals("getMethod") ? Name.PARAMETERS : Name.DECLARED_PARAMETERS) : null;
                case "getField", "getDeclaredField" -> call.desc.equals("(" + S + ")Ljava/lang/reflect/Field;")
                        ? (call.name.equals("getField") ? Name.FIELD : Name.DECLARED_FIELD) : null;
                default -> null;
            };
        }
        if (call.owner.equals("java/lang/invoke/MethodHandles$Lookup") && call.getOpcode() == INVOKEVIRTUAL) {
            return switch (call.name) {
                case "findClass" -> call.desc.equals("(" + S + ")" + C) ? Name.CLASS : null;
                case "findStatic", "findVirtual" -> call.desc.equals("(" + C + S + T + ")" + H) ? Name.METHOD : null;
                case "findSpecial" -> call.desc.equals("(" + C + S + T + C + ")" + H) ? Name.METHOD : null;
                case "findGetter", "findSetter", "findStaticGetter", "findStaticSetter" -> call.desc.equals("(" + C + S + C + ")" + H) ? Name.TYPED_FIELD : null;
                case "findVarHandle", "findStaticVarHandle" -> call.desc.equals("(" + C + S + C + ")" + V) ? Name.TYPED_FIELD : null;
                default -> null;
            };
        }
        if ((call.getOpcode() == INVOKEVIRTUAL || call.getOpcode() == INVOKESPECIAL) && call.name.equals("loadClass")
                && (call.owner.equals("java/lang/ClassLoader") || call.owner.equals("java/net/URLClassLoader")
                || call.owner.equals("java/security/SecureClassLoader"))
                && (call.desc.equals("(" + S + ")" + C) || call.desc.equals("(" + S + "Z)" + C))) return Name.CLASS;
        return null;
    }
}
