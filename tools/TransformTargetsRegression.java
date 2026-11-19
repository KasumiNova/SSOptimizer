import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.List;
import java.util.UUID;

class TransformTargetsRegression {
    static void run() throws Exception {
        String pkg = "fixture/p" + UUID.randomUUID().toString().replace("-", "") + "/";
        String allowed = pkg + "Game", unknown = pkg + "Plugin";
        var fixture = new ClassNode();
        fixture.name = pkg + "Registration";
        var annotation = new AnnotationNode(TransformTargets.MIXIN);
        annotation.values = List.of("targets", List.of(allowed.replace("/", ".")), "value", List.of(Type.getObjectType(unknown)));
        fixture.invisibleAnnotations = List.of(annotation);
        var method = new MethodNode(Opcodes.ACC_STATIC, "install", "()V", null, null);
        method.maxStack = 2;
        for (String target : List.of(allowed, unknown)) {
            method.instructions.add(new LdcInsnNode(target));
            method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TransformTargets.BOOT + "HybridWeaverTransformer", "registerProcessor", "(Ljava/lang/String;Ljava/lang/Object;)V", false));
        }
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        fixture.methods.add(method);
        var audit = new TransformTargets();
        audit.known.add(allowed);
        audit.inspect(fixture);
        if (audit.targets != 2 || audit.registrations != 2 || audit.errors.size() != 2 || audit.errors.stream().anyMatch(s -> !s.endsWith(unknown))) {
            throw new AssertionError("目标归属判定错误: " + audit.errors);
        }
    }
}
