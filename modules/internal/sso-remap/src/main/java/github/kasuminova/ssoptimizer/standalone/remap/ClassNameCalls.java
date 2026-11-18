package github.kasuminova.ssoptimizer.standalone.remap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.SourceInterpreter;

final class ClassNameCalls {
    private ClassNameCalls() {
    }

    static void rewrite(final ClassNode node, final MappingProjection mapping) {
        for (var method : node.methods) {
            boolean containsLookup = false;
            for (var instruction : method.instructions) {
                containsLookup |= instruction instanceof MethodInsnNode call && nameArgument(call) >= 0;
            }
            if (!containsLookup) {
                continue;
            }
            try {
                final var frames = new Analyzer<>(new SourceInterpreter()).analyze(node.name, method);
                for (int i = 0; i < method.instructions.size(); i++) {
                    if (!(method.instructions.get(i) instanceof MethodInsnNode call) || frames[i] == null) {
                        continue;
                    }
                    final int argument = nameArgument(call);
                    if (argument < 0) {
                        continue;
                    }
                    final int arguments = Type.getArgumentTypes(call.desc).length;
                    final var value = frames[i].getStack(frames[i].getStackSize() - arguments + argument);
                    if (value.insns.size() == 1 && value.insns.iterator().next() instanceof LdcInsnNode literal
                            && literal.cst instanceof String name && uninterrupted(literal, call)) {
                        literal.cst = binaryName(name, mapping);
                    }
                }
            } catch (AnalyzerException error) {
                throw new IllegalArgumentException("Cannot resolve class-name lookup in " + node.name + "." + method.name + method.desc, error);
            }
        }
    }

    private static boolean uninterrupted(final LdcInsnNode source, final MethodInsnNode call) {
        for (var instruction = source.getNext(); instruction != null; instruction = instruction.getNext()) {
            if (instruction == call) {
                return true;
            }
            if (instruction instanceof org.objectweb.asm.tree.JumpInsnNode
                    || instruction instanceof org.objectweb.asm.tree.TableSwitchInsnNode
                    || instruction instanceof org.objectweb.asm.tree.LookupSwitchInsnNode
                    || instruction.getOpcode() == Opcodes.RET
                    || (instruction.getOpcode() >= Opcodes.DUP && instruction.getOpcode() <= Opcodes.SWAP)) {
                return false;
            }
        }
        return false;
    }

    private static int nameArgument(final MethodInsnNode call) {
        if (call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals("java/lang/Class") && call.name.equals("forName")) {
            return switch (call.desc) {
                case "(Ljava/lang/String;)Ljava/lang/Class;", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;" -> 0;
                case "(Ljava/lang/Module;Ljava/lang/String;)Ljava/lang/Class;" -> 1;
                default -> -1;
            };
        }
        if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.name.equals("loadClass")
                && (call.owner.equals("java/lang/ClassLoader") || call.owner.equals("java/net/URLClassLoader")
                || call.owner.equals("java/security/SecureClassLoader"))) {
            return call.desc.equals("(Ljava/lang/String;)Ljava/lang/Class;")
                    || call.desc.equals("(Ljava/lang/String;Z)Ljava/lang/Class;") ? 0 : -1;
        }
        return -1;
    }

    private static String binaryName(final String name, final MappingProjection mapping) {
        if (name.startsWith("[")) {
            final int object = name.indexOf('L');
            if (object < 0 || !name.endsWith(";")) {
                return name;
            }
            return name.substring(0, object + 1) + mapped(name.substring(object + 1, name.length() - 1), mapping) + ";";
        }
        return mapped(name, mapping);
    }

    private static String mapped(final String name, final MappingProjection mapping) {
        if (name.indexOf('/') >= 0) {
            return name;
        }
        final String internal = name.replace('.', '/');
        if (mapping.isNamedOnly(internal)) {
            return name;
        }
        return mapping.mapClass(internal).replace('/', '.');
    }
}
