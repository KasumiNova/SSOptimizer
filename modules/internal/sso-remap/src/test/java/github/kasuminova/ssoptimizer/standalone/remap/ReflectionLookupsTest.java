package github.kasuminova.ssoptimizer.standalone.remap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class ReflectionLookupsTest {
    @Test void dynamicArgumentsReachOnlyTheMatchingNameTranslatorAndOriginalCall() throws Exception {
        for (Class<?> owner : List.of(Class.class, ClassLoader.class, MethodHandles.Lookup.class)) {
            for (var method : owner.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isPrivate(method.getModifiers())) continue;
                String helper = switch (method.getName()) {
                    case "forName", "findClass", "loadClass" -> "className";
                    case "getDeclaredMethod" -> "declaredMethodName";
                    case "getDeclaredField" -> "declaredFieldName";
                    case "getField", "findGetter", "findSetter", "findStaticGetter",
                            "findStaticSetter", "findVarHandle", "findStaticVarHandle" -> "fieldName";
                    case "getMethod", "findStatic", "findVirtual", "findSpecial" -> "methodName";
                    default -> null;
                };
                if (helper == null || (owner == ClassLoader.class && (!method.getName().equals("loadClass")
                        || method.getParameterTypes()[0] != String.class))) continue;
                int op = owner == Class.class && method.getName().equals("forName") ? INVOKESTATIC : INVOKEVIRTUAL;
                check(new MethodInsnNode(op, Type.getInternalName(owner), method.getName(), Type.getMethodDescriptor(method)), helper);
            }
        }
        for (String owner : List.of("java/lang/ClassLoader", "java/security/SecureClassLoader", "java/net/URLClassLoader")) {
            check(new MethodInsnNode(INVOKESPECIAL, owner, "loadClass", "(Ljava/lang/String;Z)Ljava/lang/Class;"), "className");
        }
    }

    @Test void leavesUnrelatedAndUnsupportedLookupsUntouched() {
        var node = new ClassNode();
        var m = new MethodNode();
        node.methods.add(m);
        var calls = List.of(
                new MethodInsnNode(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Class;"),
                new MethodInsnNode(INVOKEVIRTUAL, "mod/Lookup", "findClass", "(Ljava/lang/String;)Ljava/lang/Class;"),
                new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;"));
        calls.forEach(m.instructions::add);
        ReflectionLookups.rewrite(node);
        assertArrayEquals(calls.toArray(), m.instructions.toArray());
    }

    @Test void preservesNamesReadByNamedBytecodeConsumersAndTranslatesOtherReads() {
        String base = "com/genir/aitweaks/launcher/loading/";
        String classToString = "(Ljava/lang/Class;)Ljava/lang/String;";
        var symbols = new ClassNode();
        symbols.name = base + "Symbols";
        var classPath = nameRead(symbols, "getClassPath", classToString);
        var methodOrder = nameRead(symbols, "getMethodsInOrder", "(Ljava/lang/Class;)Ljava/util/List;");
        var foreignUse = nameRead(symbols, "unrelated", classToString);
        var loader = new ClassNode();
        loader.name = base + "CoreLoader";
        var transformer = nameRead(loader, "obfuscator_delegate$lambda$2", "()L" + base + "Transformer;");
        var officer = new ClassNode();
        officer.name = "mod/officer/Symbols";
        var legacy = nameRead(officer, "getClassPath", classToString);

        for (var node : List.of(symbols, loader, officer)) ReflectionLookups.rewrite(node);
        for (var call : List.of(classPath, methodOrder, transformer)) {
            assertEquals(INVOKEVIRTUAL, call.getOpcode());
            assertEquals("java/lang/Class", call.owner);
            assertEquals("getName", call.name);
        }
        for (var call : List.of(foreignUse, legacy)) {
            assertEquals(INVOKESTATIC, call.getOpcode());
            assertEquals("legacyName", call.name);
            assertEquals(classToString, call.desc);
        }
    }

    private static MethodInsnNode nameRead(ClassNode node, String methodName, String descriptor) {
        var method = new MethodNode(0, methodName, descriptor, null, null);
        node.methods.add(method);
        var call = new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
        method.instructions.add(call);
        return call;
    }

    private static void check(MethodInsnNode call, String helper) throws AnalyzerException {
        Type[] args = Type.getArgumentTypes(call.desc);
        var params = new ArrayList<Type>();
        if (call.getOpcode() != INVOKESTATIC) params.add(Type.getObjectType(call.owner));
        params.addAll(List.of(args));
        var node = new ClassNode();
        node.name = "test/Lookups";
        var m = new MethodNode(ACC_STATIC, "lookup", Type.getMethodDescriptor(Type.getReturnType(call.desc), params.toArray(Type[]::new)), null, null);
        node.methods.add(m);
        var loads = new ArrayList<AbstractInsnNode>();
        for (int i = 0; i < params.size(); i++) {
            var load = new VarInsnNode(params.get(i).getOpcode(ILOAD), i);
            loads.add(load);
            m.instructions.add(load);
        }
        m.instructions.add(call);
        m.instructions.add(new InsnNode(ARETURN));
        m.maxStack = m.maxLocals = params.size();
        int originalOp = call.getOpcode();
        String originalOwner = call.owner, originalName = call.name, originalDesc = call.desc;
        ReflectionLookups.rewrite(node);
        new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
        if (helper.equals("className")) {
            var bridge = node.methods.getLast();
            assertEquals(bridge.name, call.name);
            assertEquals(node.name, call.owner);
            assertEquals(INVOKESTATIC, call.getOpcode());
            new Analyzer<>(new BasicVerifier()).analyze(node.name, bridge);
            MethodInsnNode first = null;
            for (var insn : bridge.instructions) if (insn instanceof MethodInsnNode c) { first = c; break; }
            assertNotNull(first);
            assertEquals(originalOp, first.getOpcode());
            assertEquals(originalOwner, first.owner);
            assertEquals(originalName, first.name);
            assertEquals(originalDesc, first.desc);
            return;
        }
        var origins = new Analyzer<>(new SourceInterpreter(ASM9) {
            @Override public SourceValue copyOperation(AbstractInsnNode insn, SourceValue value) {
                return loads.contains(insn) ? new SourceValue(1, insn) : value;
            }
        }).analyze(node.name, m);
        var calls = new ArrayList<MethodInsnNode>();
        for (var insn : m.instructions) if (insn instanceof MethodInsnNode c) calls.add(c);
        assertEquals(2, calls.size(), call.name + call.desc);
        assertSame(call, calls.get(1));
        var translated = calls.getFirst();
        assertEquals(INVOKESTATIC, translated.getOpcode());
        assertEquals("github/kasuminova/ssoptimizer/standalone/remap/reflect/ReflectionNames", translated.owner);
        assertEquals(helper, translated.name);
        int name = params.indexOf(Type.getType(String.class));
        var atCall = origins[m.instructions.indexOf(call)];
        for (int i = 0; i < params.size(); i++) assertEquals(Set.of(i == name ? translated : loads.get(i)), atCall.getStack(i).insns);
        var atHelper = origins[m.instructions.indexOf(translated)];
        int count = Type.getArgumentCount(translated.desc);
        int start = helper.equals("className") ? name : name - 1;
        assertEquals(Type.getMethodDescriptor(Type.getType(String.class), params.subList(start, start + count).toArray(Type[]::new)), translated.desc);
        for (int i = 0; i < count; i++) assertEquals(Set.of(loads.get(start + i)), atHelper.getStack(atHelper.getStackSize() - count + i).insns);
    }

    @Test void originalStackMapFramesVerifyAcrossTheRewrittenBranch() throws Throwable {
        var node = new ClassNode();
        node.visit(V17, ACC_PUBLIC, Type.getInternalName(getClass()) + "Branch", null, "java/lang/Object", null);
        var m = node.visitMethod(ACC_PUBLIC | ACC_STATIC, "choose", "(ZLjava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/Object;", null, null);
        var plain = new org.objectweb.asm.Label();
        m.visitVarInsn(ILOAD, 0);
        m.visitJumpInsn(IFEQ, plain);
        m.visitVarInsn(ALOAD, 1); m.visitVarInsn(ALOAD, 2); m.visitVarInsn(ALOAD, 3);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
        m.visitInsn(ARETURN);
        m.visitLabel(plain);
        m.visitFrame(F_SAME, 0, null, 0, null);
        m.visitVarInsn(ALOAD, 2); m.visitInsn(ARETURN);
        m.visitMaxs(3, 4);
        ReflectionLookups.rewrite(node);
        var writer = new ClassWriter(0);
        node.accept(writer);
        var lookup = MethodHandles.lookup().defineHiddenClass(writer.toByteArray(), true);
        var choose = lookup.findStatic(lookup.lookupClass(), "choose", MethodType.methodType(Object.class, boolean.class, Class.class, String.class, Class[].class));
        String original = "unrelated";
        assertSame(original, choose.invoke(false, String.class, original, new Class<?>[0]));
    }
}
