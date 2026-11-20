// RetroFuturaBootstrap 1.0.12 的独立版构建补丁。依据 LGPL-3.0 分发。
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class PatchRfbDelegation implements Opcodes {
    private static final String SYSTEM = "com/gtnewhorizons/retrofuturabootstrap/RfbSystemClassLoader";
    private static final String CHILD = "com/gtnewhorizons/retrofuturabootstrap/api/ExtensibleClassLoader";
    private static final String FIND_CLASS = "(Ljava/lang/String;)Ljava/lang/Class;";

    public static void main(String[] args) throws IOException {
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        try (var source = new ZipFile(input.toFile())) {
            var systemClass = source.getEntry(SYSTEM + ".class");
            if (systemClass == null) throw new IOException("RFB 缺少 SystemClassLoader: " + input);
            final byte[] patched;
            try (var stream = source.getInputStream(systemClass)) {
                patched = patch(stream.readAllBytes());
            }
            try (var target = new ZipOutputStream(Files.newOutputStream(output))) {
                var entries = source.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    var next = new ZipEntry(entry.getName());
                    next.setTime(entry.getTime());
                    target.putNextEntry(next);
                    if (entry.getName().equals(systemClass.getName())) {
                        target.write(patched);
                    } else if (!entry.isDirectory()) {
                        try (var stream = source.getInputStream(entry)) {
                            stream.transferTo(target);
                        }
                    }
                    target.closeEntry();
                }
            }
        }
    }

    private static byte[] patch(byte[] original) {
        var reader = new ClassReader(original);
        var writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                var next = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("findClass") || !descriptor.equals(FIND_CLASS)) return next;
                return new MethodNode(ASM9, access, name, descriptor, signature, exceptions) {
                    @Override
                    public void visitEnd() {
                        patchFindClass(this);
                        accept(next);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void patchFindClass(MethodNode method) {
        VarInsnNode resource = null;
        VarInsnNode systemSource = null;
        VarInsnNode delegateGuard = null;
        VarInsnNode delegating = null;
        MethodInsnNode unsealedPackage = null;
        AbstractInsnNode missing = null;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals(SYSTEM) && call.name.equals("findCodeSourceConnectionFor")) {
                    resource = local(previousInstruction(call), ALOAD);
                    systemSource = local(nextInstruction(call), ASTORE);
                }
                if (call.owner.equals(SYSTEM) && call.name.equals("getAndVerifyPackage")
                        && previousInstruction(call).getOpcode() == ACONST_NULL
                        && previousInstruction(previousInstruction(call)).getOpcode() == ACONST_NULL) {
                    unsealedPackage = call;
                }
                if (call.owner.equals("java/util/HashSet") && call.name.equals("add") && call.desc.equals("(Ljava/lang/Object;)Z")) {
                    delegateGuard = local(previousInstruction(previousInstruction(call)), ALOAD);
                    delegating = local(nextInstruction(call), ISTORE);
                }
            } else if (instruction instanceof LdcInsnNode literal && "Class bytes are null for %s (%s, %s)".equals(literal.cst)) {
                missing = previousInstruction(previousInstruction(literal));
                if (missing.getOpcode() != NEW) throw new IllegalStateException("RFB 1.0.12 缺少空字节异常入口");
            }
        }
        if (resource == null || systemSource == null || delegateGuard == null || delegating == null
                || unsealedPackage == null || missing == null
                || !(previousInstruction(missing) instanceof JumpInsnNode hasClass) || hasClass.getOpcode() != IFNONNULL) {
            throw new IllegalStateException("RFB 1.0.12 的 findClass 结构与补丁不匹配");
        }
        deferPackage(method, systemSource.var, unsealedPackage, hasClass.label);
        var childLoad = nextInstruction(delegating);
        var delegation = method.tryCatchBlocks.stream()
                .filter(block -> block.type == null && nextInstruction(block.start) == childLoad)
                .findFirst().orElseThrow(() -> new IllegalStateException("RFB 1.0.12 缺少子加载委派的清理入口"));
        int child = method.maxLocals++;
        int resourceName = method.maxLocals++;
        var noChildCandidate = new LabelNode();
        var probeStart = new LabelNode();
        var probeEnd = new LabelNode();
        var lookup = new InsnList();
        lookup.add(new VarInsnNode(ALOAD, systemSource.var));
        lookup.add(new JumpInsnNode(IFNONNULL, noChildCandidate));
        lookup.add(new VarInsnNode(ALOAD, 0));
        lookup.add(new FieldInsnNode(GETFIELD, SYSTEM, "childLoader", "L" + CHILD + ";"));
        lookup.add(new VarInsnNode(ASTORE, child));
        lookup.add(new VarInsnNode(ALOAD, child));
        lookup.add(new JumpInsnNode(IFNULL, noChildCandidate));
        lookup.add(new VarInsnNode(ALOAD, resource.var));
        lookup.add(new VarInsnNode(ASTORE, resourceName));
        lookup.add(new VarInsnNode(ALOAD, delegateGuard.var));
        lookup.add(new VarInsnNode(ALOAD, 1));
        lookup.add(new MethodInsnNode(INVOKEVIRTUAL, "java/util/HashSet", "add", "(Ljava/lang/Object;)Z", false));
        lookup.add(new VarInsnNode(ISTORE, delegating.var));
        lookup.add(probeStart);
        lookup.add(new VarInsnNode(ALOAD, child));
        lookup.add(new VarInsnNode(ALOAD, 1));
        lookup.add(new MethodInsnNode(INVOKEINTERFACE, CHILD, "findCachedClass", FIND_CLASS, true));
        lookup.add(new JumpInsnNode(IFNONNULL, delegation.start));
        lookup.add(new VarInsnNode(ALOAD, child));
        lookup.add(new MethodInsnNode(INVOKEINTERFACE, CHILD, "asURLClassLoader", "()Ljava/net/URLClassLoader;", true));
        lookup.add(new VarInsnNode(ALOAD, resourceName));
        lookup.add(new MethodInsnNode(INVOKEVIRTUAL, "java/net/URLClassLoader", "findResource", "(Ljava/lang/String;)Ljava/net/URL;", false));
        lookup.add(new JumpInsnNode(IFNONNULL, delegation.start));
        lookup.add(probeEnd);
        lookup.add(new VarInsnNode(ALOAD, delegateGuard.var));
        lookup.add(new VarInsnNode(ALOAD, 1));
        lookup.add(new MethodInsnNode(INVOKEVIRTUAL, "java/util/HashSet", "remove", "(Ljava/lang/Object;)Z", false));
        lookup.add(new InsnNode(POP));
        lookup.add(noChildCandidate);
        method.instructions.insertBefore(missing, lookup);
        method.tryCatchBlocks.add(new TryCatchBlockNode(probeStart, probeEnd, delegation.handler, null));
    }

    private static void deferPackage(MethodNode method, int systemSource, MethodInsnNode definePackage, LabelNode hasClass) {
        var packageName = local(previousInstruction(previousInstruction(previousInstruction(definePackage))), ALOAD);
        var receiver = previousInstruction(packageName);
        local(receiver, ALOAD);
        var skip = new LabelNode();
        var guard = new InsnList();
        guard.add(new VarInsnNode(ALOAD, systemSource));
        guard.add(new JumpInsnNode(IFNULL, skip));
        method.instructions.insertBefore(receiver, guard);
        method.instructions.insert(local(nextInstruction(definePackage), ASTORE), skip);

        // 变换器可在没有来源的情况下生成类。只有收到字节后才定义其包。
        var defined = new LabelNode();
        var deferred = new InsnList();
        deferred.add(new VarInsnNode(ALOAD, systemSource));
        deferred.add(new JumpInsnNode(IFNONNULL, defined));
        deferred.add(new VarInsnNode(ALOAD, packageName.var));
        deferred.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false));
        deferred.add(new JumpInsnNode(IFNE, defined));
        deferred.add(new VarInsnNode(ALOAD, 0));
        deferred.add(new VarInsnNode(ALOAD, packageName.var));
        deferred.add(new InsnNode(ACONST_NULL));
        deferred.add(new InsnNode(ACONST_NULL));
        deferred.add(new MethodInsnNode(definePackage.getOpcode(), definePackage.owner, definePackage.name, definePackage.desc));
        deferred.add(new InsnNode(POP));
        deferred.add(defined);
        method.instructions.insert(hasClass, deferred);
    }

    private static VarInsnNode local(AbstractInsnNode instruction, int opcode) {
        if (instruction instanceof VarInsnNode variable && variable.getOpcode() == opcode) return variable;
        throw new IllegalStateException("RFB 1.0.12 的 findClass 局部变量与补丁不匹配");
    }

    private static AbstractInsnNode previousInstruction(AbstractInsnNode instruction) {
        var node = instruction.getPrevious();
        while (node.getOpcode() < 0) node = node.getPrevious();
        return node;
    }

    private static AbstractInsnNode nextInstruction(AbstractInsnNode instruction) {
        var node = instruction.getNext();
        while (node.getOpcode() < 0) node = node.getNext();
        return node;
    }
}
