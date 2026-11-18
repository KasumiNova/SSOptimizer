package ssoptimizer.standalone.host;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** 模组类和调用方法均不固定，只能在已登记归档中按 CodeSource 调用指令识别。 */
public final class ModCodeSourceTransformer implements IClassTransformer {
    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null || !PreparedSources.isModClass(name)) return bytes;
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        boolean[] changed = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, sig, exceptions)) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                        super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                        if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("java/security/CodeSource")
                                && name.equals("getLocation") && descriptor.equals("()Ljava/net/URL;")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "ssoptimizer/standalone/host/PreparedSources",
                                    "namedJar", "(Ljava/net/URL;)Ljava/net/URL;", false);
                            changed[0] = true;
                        }
                    }
                };
            }
        }, 0);
        return changed[0] ? writer.toByteArray() : bytes;
    }
}
