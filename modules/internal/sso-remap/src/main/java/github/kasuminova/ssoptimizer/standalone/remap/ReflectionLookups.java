package github.kasuminova.ssoptimizer.standalone.remap;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import java.util.List;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import static org.objectweb.asm.Opcodes.*;

final class ReflectionLookups {
    private static final String NAMES = "github/kasuminova/ssoptimizer/standalone/remap/reflect/ReflectionNames";
    private static final String C = "Ljava/lang/Class;", S = "Ljava/lang/String;", T = "Ljava/lang/invoke/MethodType;";
    private static final String H = "Ljava/lang/invoke/MethodHandle;", V = "Ljava/lang/invoke/VarHandle;";
    private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";
    private static final String AI_TWEAKS_LOADING = "com/genir/aitweaks/launcher/loading/";
    private static final int LAMBDA_IMPL = 1, LAMBDA_FLAGS = 3, LAMBDA_SERIALIZABLE = 1;

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
            if (!readsNamedBytecode(node.name, method)) legacyNames(method);
        }
    }

    // AITweaks 使用 JVM 类名读取 named 归档、构造描述符和生成其私有核心的重写表。
    private static boolean readsNamedBytecode(String owner, MethodNode method) {
        if ((AI_TWEAKS_LOADING + "Symbols").equals(owner)) {
            return switch (method.name) {
                case "getClassPath" -> method.desc.equals("(" + C + ")" + S);
                case "getMethodsInOrder" -> method.desc.equals("(" + C + ")Ljava/util/List;");
                default -> false;
            };
        }
        return (AI_TWEAKS_LOADING + "CoreLoader").equals(owner)
                && method.name.startsWith("obfuscator_delegate$lambda$")
                && method.desc.equals("()L" + AI_TWEAKS_LOADING + "Transformer;");
    }

    private static void legacyNames(MethodNode method) {
        for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != INVOKEVIRTUAL
                    || !call.owner.equals("java/lang/Class") || !call.name.equals("getName")
                    || !call.desc.equals("()" + S)) {
                continue;
            }
            call.setOpcode(INVOKESTATIC);
            call.owner = NAMES;
            call.name = "legacyName";
            call.desc = "(" + C + ")" + S;
            call.itf = false;
        }
        for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof InvokeDynamicInsnNode indy) {
                Handle handle = lambdaImpl(indy);
                if (handle == null || !legacyHandle(handle)) continue;
                indy.bsmArgs[LAMBDA_IMPL] = new Handle(H_INVOKESTATIC, NAMES, "legacyName", "(" + C + ")" + S, false);
            }
        }
    }

    private static Handle lambdaImpl(InvokeDynamicInsnNode indy) {
        if (indy.bsm.getTag() != H_INVOKESTATIC || !indy.bsm.getOwner().equals(LAMBDA_METAFACTORY)) return null;
        boolean compatible = switch (indy.bsm.getName()) {
            case "metafactory" -> indy.bsmArgs.length == 3;
            // $deserializeLambda$ 校验序列化数据中的原实现句柄。
            case "altMetafactory" -> indy.bsmArgs.length > LAMBDA_FLAGS
                    && indy.bsmArgs[LAMBDA_FLAGS] instanceof Integer flags && (flags & LAMBDA_SERIALIZABLE) == 0;
            default -> false;
        };
        return compatible && indy.bsmArgs[LAMBDA_IMPL] instanceof Handle handle ? handle : null;
    }

    private static boolean legacyHandle(Handle handle) {
        return handle.getTag() == H_INVOKEVIRTUAL
                && handle.getOwner().equals("java/lang/Class") && handle.getName().equals("getName")
                && handle.getDesc().equals("()" + S);
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
