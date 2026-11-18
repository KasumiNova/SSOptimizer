package github.kasuminova.ssoptimizer.standalone.remap;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

final class ClassLookups {
    private static final String NAMES = "github/kasuminova/ssoptimizer/standalone/remap/reflect/ReflectionNames";
    private static final String CLASS = "java/lang/Class", MISSING = "java/lang/ClassNotFoundException";
    private static final String EXPLICIT = "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;";
    private record Call(int op, String owner, String name, String desc) { }
    private final Map<Call,MethodNode> helpers = new HashMap<>();
    private final Set<String> names = new HashSet<>();
    private final ClassNode caller;
    private int serial;

    ClassLookups(ClassNode caller) { this.caller = caller; }

    void replace(MethodInsnNode original) {
        if ((caller.access & ACC_INTERFACE) != 0 && (caller.version & 0xffff) < V1_8) caller.version = V1_8;
        if (helpers.isEmpty()) caller.methods.forEach(method -> names.add(method.name));
        var key = new Call(original.getOpcode(), original.owner, original.name, original.desc);
        var helper = helpers.computeIfAbsent(key, this::generate);
        original.setOpcode(INVOKESTATIC);
        original.owner = caller.name;
        original.name = helper.name;
        original.desc = helper.desc;
        original.itf = (caller.access & ACC_INTERFACE) != 0;
    }

    private MethodNode generate(Call call) {
        var args = new java.util.ArrayList<>(Arrays.asList(Type.getArgumentTypes(call.desc)));
        if (call.op != INVOKESTATIC) {
            boolean protectedReceiver = call.op == INVOKESPECIAL || call.name.equals("loadClass") && call.desc.startsWith("(Ljava/lang/String;Z");
            args.addFirst(Type.getObjectType(protectedReceiver ? caller.name : call.owner));
        }
        var params = args.toArray(Type[]::new);
        int name = args.indexOf(Type.getType(String.class));
        String desc = Type.getMethodDescriptor(Type.getType(Class.class), params);
        String id;
        do { id = "$sso$classLookup$" + serial++; } while (!names.add(id));
        var method = new MethodNode(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, id, desc, null, null);
        boolean module = call.desc.startsWith("(Ljava/lang/Module;");
        boolean init = call.name.equals("forName") && !module;
        int missing = args.size(), mapped = missing + 1, candidate = mapped + 1;
        Label start = new Label(), end = new Label(), handler = new Label(), retry = new Label(), reject = new Label(), found = new Label();
        method.visitLabel(start);
        invoke(method, call, params, name, name);
        method.visitLabel(end);
        if (module) {
            method.visitInsn(DUP);
            method.visitJumpInsn(IFNONNULL, found);
            method.visitInsn(POP);
            method.visitInsn(ACONST_NULL);
        } else {
            method.visitInsn(ARETURN);
            method.visitTryCatchBlock(start, end, handler, MISSING);
            method.visitLabel(handler);
        }
        method.visitVarInsn(ASTORE, missing);
        method.visitVarInsn(ALOAD, name);
        method.visitMethodInsn(INVOKESTATIC, NAMES, "className", "(Ljava/lang/String;)Ljava/lang/String;", false);
        method.visitVarInsn(ASTORE, mapped);
        method.visitVarInsn(ALOAD, name);
        method.visitVarInsn(ALOAD, mapped);
        method.visitMethodInsn(INVOKESTATIC, "java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
        method.visitJumpInsn(IFNE, reject);
        method.visitLabel(retry);
        if (init) {
            method.visitVarInsn(ALOAD, mapped);
            method.visitInsn(ICONST_0);
            if (params.length == 3) method.visitVarInsn(ALOAD, 2);
            else {
                if ((caller.version & 0xffff) >= V1_5) method.visitLdcInsn(Type.getObjectType(caller.name));
                else {
                    method.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
                    method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "lookupClass", "()Ljava/lang/Class;", false);
                }
                method.visitMethodInsn(INVOKEVIRTUAL, CLASS, "getClassLoader", "()Ljava/lang/ClassLoader;", false);
            }
            method.visitMethodInsn(INVOKESTATIC, CLASS, "forName", EXPLICIT, false);
        } else invoke(method, call, params, name, mapped);
        method.visitVarInsn(ASTORE, candidate);
        method.visitVarInsn(ALOAD, candidate);
        method.visitMethodInsn(INVOKESTATIC, NAMES, "isGameClass", "(Ljava/lang/Class;)Z", false);
        method.visitJumpInsn(IFEQ, reject);
        if (init) {
            if (params.length == 3) {
                Label initialize = new Label();
                method.visitVarInsn(ILOAD, 1);
                method.visitJumpInsn(IFNE, initialize);
                method.visitVarInsn(ALOAD, candidate);
                method.visitInsn(ARETURN);
                method.visitLabel(initialize);
            }
            invoke(method, call, params, name, mapped);
        } else method.visitVarInsn(ALOAD, candidate);
        method.visitLabel(found);
        method.visitInsn(ARETURN);
        if (!module) {
            Label failed = new Label();
            method.visitTryCatchBlock(retry, found, failed, MISSING);
            method.visitLabel(failed);
            method.visitInsn(POP);
        }
        method.visitLabel(reject);
        method.visitVarInsn(ALOAD, missing);
        method.visitInsn(module ? ARETURN : ATHROW);
        // Calculate frames for the generated branches without changing frames in user methods.
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(V17, ACC_PUBLIC, caller.name, null, "java/lang/Object", null);
        method.accept(writer);
        var one = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(one, ClassReader.EXPAND_FRAMES);
        MethodNode framed = one.methods.getFirst();
        caller.methods.add(framed);
        return framed;
    }

    private static void invoke(MethodNode method, Call call, Type[] params, int name, int nameSlot) {
        for (int i = 0; i < params.length; i++) method.visitVarInsn(params[i].getOpcode(ILOAD), i == name ? nameSlot : i);
        method.visitMethodInsn(call.op, call.owner, call.name, call.desc, false);
    }
}
