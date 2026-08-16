package github.kasuminova.ssoptimizer.asm.ai;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.apache.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * FighterAI 编队分组键接口织入的 ASM 处理器。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.ai.FighterAI}<br>
 * 注入动机：并行 AI 调度需要按 {@code FighterWing} 分组串行，但 {@code wing}
 * 字段为 private 且项目规范禁止反射；Mixin accessor 可以读字段，但调度器在
 * 纯 JVM 层运行、不经过 Mixin 通道时接口注入更直接。<br>
 * 注入效果：类声明追加
 * {@code implements WingKeyProvider}，并新增方法
 * {@code public Object ssoptimizer$getWingKey() { return this.wing; }}。
 */
public final class FighterAIWingKeyProcessor implements AsmClassProcessor {
    private static final Logger LOGGER = Logger.getLogger(FighterAIWingKeyProcessor.class);

    private static final String TARGET_CLASS      = GameClassNames.FIGHTER_AI;
    private static final String PROVIDER_INTERFACE = "github/kasuminova/ssoptimizer/common/combat/ai/WingKeyProvider";

    @Override
    public byte[] process(byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        ClassNode node = new ClassNode(Opcodes.ASM9);
        reader.accept(node, 0);

        boolean hasWingField = node.fields.stream()
                .anyMatch(f -> "wing".equals(f.name) && "Lcom/fs/starfarer/combat/ai/FighterWing;".equals(f.desc));
        if (!hasWingField) {
            LOGGER.warn("[SSOptimizer] FighterAI.wing field not found; WingKeyProvider not injected");
            return null;
        }

        node.interfaces.add(PROVIDER_INTERFACE);

        MethodNode getter = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC,
                "ssoptimizer$getWingKey", "()Ljava/lang/Object;", null, null);
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,
                TARGET_CLASS, "wing", "Lcom/fs/starfarer/combat/ai/FighterWing;"));
        getter.instructions.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(getter);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        node.accept(writer);
        return writer.toByteArray();
    }
}
