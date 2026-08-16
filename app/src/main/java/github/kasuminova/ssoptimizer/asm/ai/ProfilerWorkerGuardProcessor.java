package github.kasuminova.ssoptimizer.asm.ai;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.apache.log4j.Logger;
import org.objectweb.asm.*;

/**
 * 原版 Profiler 的 AI 工作线程守卫 ASM 处理器。
 * <p>
 * 注入目标：{@code com.fs.profiler.Profiler}<br>
 * 注入动机：{@code begin(String)} / {@code end()} 在启用时操作静态栈
 * （{@code sampleStack} 等），AI 工作线程若触碰会 corrupt 主线程的采样栈
 * （如 {@code AIUtils.areHulksInTheWay} 内的 {@code Profiler.begin}）。<br>
 * 注入效果：在 {@code begin(String)} 与 {@code end()} 方法头插入
 * {@code if (ParallelAiDispatcher.isWorkerThread()) return;}，工作线程完全绕过
 * 原版采样；主线程行为不变。未启用时（enabled=false）这两个方法本就空转，守卫
 * 仅多一次线程判断，开销可忽略。
 */
public final class ProfilerWorkerGuardProcessor implements AsmClassProcessor {
    private static final Logger LOGGER = Logger.getLogger(ProfilerWorkerGuardProcessor.class);

    private static final String TARGET_CLASS    = GameClassNames.PROFILER;
    private static final String DISPATCH_OWNER  = "github/kasuminova/ssoptimizer/common/combat/ai/ParallelAiDispatcher";

    @Override
    public byte[] process(byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final int[] guarded = {0};
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, sig, ex);
                boolean target = ("begin".equals(name) && "(Ljava/lang/String;)V".equals(desc))
                        || ("end".equals(name) && "()V".equals(desc));
                if (!target) {
                    return delegate;
                }
                guarded[0]++;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        Label proceed = new Label();
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, DISPATCH_OWNER, "isWorkerThread", "()Z", false);
                        super.visitJumpInsn(Opcodes.IFEQ, proceed);
                        super.visitInsn(Opcodes.RETURN);
                        super.visitLabel(proceed);
                    }
                };
            }
        }, 0);

        if (guarded[0] != 2) {
            LOGGER.warn("[SSOptimizer] ProfilerWorkerGuardProcessor expected 2 methods, guarded " + guarded[0]);
            return null;
        }
        return writer.toByteArray();
    }
}
