package github.kasuminova.ssoptimizer.asm.ime;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.*;

/**
 * LWJGL {@code LinuxEvent} 的 IME ASM 处理器。
 *
 * <p>注入目标：{@code org/lwjgl/opengl/LinuxEvent#filterEvent(J)Z}<br>
 * 注入动机：原版 {@code filterEvent} 调用 native {@code nFilterEvent}（即 {@code XFilterEvent}），
 * 使 IM 服务（如 fcitx）把每个事件看到两次——一次来自 LWJGL、一次来自 SSOptimizer 自身的 XIC，
 * 导致组字状态损坏。整体替换方法体为 {@code return false} 后，SSOptimizer 的 native 代码成为
 * {@code XFilterEvent} 的唯一调用方。<br>
 * 为什么不是 Mixin：{@code org.lwjgl.} 被 RFB 硬编码为 classLoaderException，由系统类加载器
 * 加载，Launch 域 Mixin 链不可触及——本处理器须经 NanoForge 的 SystemAsmBridge（RFB 插件
 * transformer）通道注册。<br>
 * 注入效果：{@code filterEvent(J)Z} 方法体替换为恒返回 {@code false}（事件未被消费）。</p>
 */
public final class LinuxEventImeProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = "org/lwjgl/opengl/LinuxEvent";

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1,
                                                 final String type2) {
                return "java/lang/Object";
            }
        };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access,
                                             final String name,
                                             final String desc,
                                             final String signature,
                                             final String[] exceptions) {
                if ("filterEvent".equals(name) && "(J)Z".equals(desc)) {
                    modified[0] = true;
                    final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                    mv.visitCode();
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.IRETURN);
                    mv.visitMaxs(1, 3);
                    mv.visitEnd();
                    return null;
                }
                return super.visitMethod(access, name, desc, signature, exceptions);
            }
        }, ClassReader.SKIP_DEBUG);

        return modified[0] ? writer.toByteArray() : null;
    }
}
