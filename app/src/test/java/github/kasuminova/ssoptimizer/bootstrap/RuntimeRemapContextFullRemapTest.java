package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全量 deobf 模式下 mod 字节码的覆写逻辑测试。
 * <p>
 * 模拟引用混淆名（类 / 字段 / 方法，含 ref-via-subclass 继承别名）的 mod 类，
 * 验证经 {@link RuntimeRemapContext} remap 后所有引用被改写为 named 命名。
 */
class RuntimeRemapContextFullRemapTest {

    @Test
    void remapsModClassReferencesToObfuscatedGameNames() {
        TinyV2MappingRepository repository = TinyV2MappingRepository.of(List.of(
            MappingEntry.classEntry("com/fs/obf/Engine", "com/fs/named/Engine"),
            MappingEntry.methodEntry("com/fs/obf/Engine", "com/fs/named/Engine", "obfTick", "tick", "()V"),
            MappingEntry.classEntry("com/fs/obf/Sub", "com/fs/named/Sub"),
            // InheritedMemberPropagator 产物的子类别名条目（ref-via-subclass 场景）
            MappingEntry.fieldEntry("com/fs/obf/Sub", "com/fs/named/Sub", "obfRate", "rate", "I")
        ));
        RuntimeRemapContext context = new RuntimeRemapContext(repository);

        byte[] remapped = context.remap("mod/example/TestMod", createModClass());
        assertNotNull(remapped, "mod 类引用了混淆名，应发生改写");

        List<String> references = collectReferences(remapped);
        assertTrue(references.contains("field:com/fs/named/Sub.rate:I"),
                "子类字段引用应改写为 named: " + references);
        assertTrue(references.contains("method:com/fs/named/Engine.tick:()V"),
                "方法引用应改写为 named: " + references);
        assertTrue(references.contains("type:com/fs/named/Engine"),
                "NEW 指令的类型引用应改写为 named: " + references);
    }

    private static byte[] createModClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8,
                Opcodes.ACC_PUBLIC,
                "mod/example/TestMod",
                null,
                "java/lang/Object",
                null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor run = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        run.visitCode();
        // ref-via-subclass：字段声明在父类，引用走子类（继承别名条目覆盖）
        run.visitFieldInsn(Opcodes.GETSTATIC, "com/fs/obf/Sub", "obfRate", "I");
        run.visitInsn(Opcodes.POP);
        run.visitMethodInsn(Opcodes.INVOKESTATIC, "com/fs/obf/Engine", "obfTick", "()V", false);
        run.visitTypeInsn(Opcodes.NEW, "com/fs/obf/Engine");
        run.visitInsn(Opcodes.DUP);
        run.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/fs/obf/Engine", "<init>", "()V", false);
        run.visitInsn(Opcodes.POP);
        run.visitInsn(Opcodes.RETURN);
        run.visitMaxs(2, 0);
        run.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> collectReferences(byte[] bytecode) {
        List<String> references = new ArrayList<>();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        references.add("field:" + owner + "." + fieldName + ":" + fieldDescriptor);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        references.add("method:" + owner + "." + methodName + ":" + methodDescriptor);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        references.add("type:" + type);
                    }
                };
            }
        }, 0);
        return references;
    }
}
