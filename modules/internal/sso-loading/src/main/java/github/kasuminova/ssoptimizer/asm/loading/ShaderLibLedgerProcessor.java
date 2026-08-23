package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 目标类：{@code org/dark/shaders/util/ShaderLib}（GraphicsLib 模组）。<br>
 * 注入位置：
 * <ul>
 * <li>{@code init()V} 全部 RETURN 前：以静态字段（shadersAllowed/buffersAllowed/
 * auxiliaryBuffer64Bit/RTTSizeX/RTTSizeY/screenTex/foregroundBufferTex/auxiliaryBufferTex）
 * 调 {@code GlLedgerHooks.noteShaderLibInit}；</li>
 * <li>{@code makeFramebuffer(IIIII)I} 全部 IRETURN 前：返回值非 0 时以
 * 返回值/第 3/4 参（宽高）调 {@code noteShaderLibRenderbuffer}；</li>
 * <li>{@code getInternalWidth()I} / {@code getInternalHeight()I} 全部 IRETURN 前：
 * DUP 返回值调 {@code noteShaderLibInternalWidth/Height}（缓存内部分辨率供
 * LightShader 埋点计量）。</li>
 * </ul>
 * 注入动机：GL 显存分类账本（GlMemoryLedger）计量 GraphicsLib 直接分配的屏幕缓冲
 * 纹理与 renderbuffer（绕过 LazyTextureManager 的非受管分配）。<br>
 * <p>
 * 为什么不用 Mixin：Mixin 0.8.7 在 EnvironmentStateTweaker 初始化时（游戏 ModManager
 * 把模组 jar 挂载到 LaunchClassLoader 之前）一次性 prepare 全部 config，模组类字节
 * 不可得导致 MixinInfo 永久失效（运行时实测：{@code @Mixin target
 * org.dark.shaders.util.ShaderLib was not found}），迟到 addConfiguration 也不会被
 * 重新 select（MixinProcessor.checkSelect 要求 transformedCount==0）。ASM 处理器在
 * 类实际经 LaunchClassLoader 加载时才介入，不受解析时序影响。<br>
 * <p>
 * 处理器落在 sso-loading 而非 sso-modopt：账本钩子类 GlLedgerHooks 在 sso-loading，
 * 同模块可编译期核对钩子签名（sso-modopt 不允许依赖 sso-loading，只能发字符串
 * INVOKESTATIC，签名漂移只能在运行期暴露）。
 */
public final class ShaderLibLedgerProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = "org/dark/shaders/util/ShaderLib";
    /** 账本钩子类（sso-loading 同模块，签名为编译期核对）。 */
    public static final String HOOK_OWNER = "github/kasuminova/ssoptimizer/common/loading/GlLedgerHooks";

    public static final String INIT_METHOD = "init";
    public static final String INIT_DESC = "()V";
    public static final String MAKE_FB_METHOD = "makeFramebuffer";
    public static final String MAKE_FB_DESC = "(IIIII)I";
    public static final String INTERNAL_W_METHOD = "getInternalWidth";
    public static final String INTERNAL_H_METHOD = "getInternalHeight";
    public static final String INT_RETURN_DESC = "()I";

    public static final String NOTE_INIT = "noteShaderLibInit";
    public static final String NOTE_INIT_DESC = "(ZZZIIIII)V";
    public static final String NOTE_RBO = "noteShaderLibRenderbuffer";
    public static final String NOTE_RBO_DESC = "(III)V";
    public static final String NOTE_INTERNAL_W = "noteShaderLibInternalWidth";
    public static final String NOTE_INTERNAL_H = "noteShaderLibInternalHeight";
    public static final String NOTE_INT_DESC = "(I)V";

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                return "java/lang/Object";
            }
        };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access,
                                             final String name,
                                             final String descriptor,
                                             final String signature,
                                             final String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (INIT_METHOD.equals(name) && INIT_DESC.equals(descriptor)) {
                    modified[0] = true;
                    return injectInitHook(delegate);
                }
                if (MAKE_FB_METHOD.equals(name) && MAKE_FB_DESC.equals(descriptor)) {
                    modified[0] = true;
                    return injectMakeFramebufferHook(delegate);
                }
                if (INTERNAL_W_METHOD.equals(name) && INT_RETURN_DESC.equals(descriptor)) {
                    modified[0] = true;
                    return injectIntReturnHook(delegate, NOTE_INTERNAL_W);
                }
                if (INTERNAL_H_METHOD.equals(name) && INT_RETURN_DESC.equals(descriptor)) {
                    modified[0] = true;
                    return injectIntReturnHook(delegate, NOTE_INTERNAL_H);
                }
                return delegate;
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    /**
     * init()V 每个 RETURN 前插入 noteShaderLibInit(8 个静态字段)。
     */
    private static MethodVisitor injectInitHook(final MethodVisitor delegate) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            @Override
            public void visitInsn(final int opcode) {
                if (opcode == Opcodes.RETURN) {
                    getStatic(mv, "shadersAllowed", "Z");
                    getStatic(mv, "buffersAllowed", "Z");
                    getStatic(mv, "auxiliaryBuffer64Bit", "Z");
                    getStatic(mv, "RTTSizeX", "I");
                    getStatic(mv, "RTTSizeY", "I");
                    getStatic(mv, "screenTex", "I");
                    getStatic(mv, "foregroundBufferTex", "I");
                    getStatic(mv, "auxiliaryBufferTex", "I");
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, NOTE_INIT, NOTE_INIT_DESC, false);
                }
                super.visitInsn(opcode);
            }
        };
    }

    /**
     * makeFramebuffer(IIIII)I 每个 IRETURN 前：返回值 <= 0 跳过，否则
     * noteShaderLibRenderbuffer(返回值, 第3参w, 第4参h)。栈形保持 [ret] 不变。
     */
    private static MethodVisitor injectMakeFramebufferHook(final MethodVisitor delegate) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            @Override
            public void visitInsn(final int opcode) {
                if (opcode == Opcodes.IRETURN) {
                    final Label skip = new Label();
                    mv.visitInsn(Opcodes.DUP);              // [ret, ret]
                    mv.visitJumpInsn(Opcodes.IFLE, skip);   // [ret]
                    mv.visitInsn(Opcodes.DUP);              // [ret, ret]
                    mv.visitVarInsn(Opcodes.ILOAD, 2);      // [ret, ret, w]
                    mv.visitVarInsn(Opcodes.ILOAD, 3);      // [ret, ret, w, h]
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, NOTE_RBO, NOTE_RBO_DESC, false);
                    mv.visitLabel(skip);                    // [ret]
                }
                super.visitInsn(opcode);
            }
        };
    }

    /**
     * int 返回方法每个 IRETURN 前：DUP 返回值调 noteXxx(I)V，栈形保持 [ret] 不变。
     */
    private static MethodVisitor injectIntReturnHook(final MethodVisitor delegate, final String hook) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            @Override
            public void visitInsn(final int opcode) {
                if (opcode == Opcodes.IRETURN) {
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, hook, NOTE_INT_DESC, false);
                }
                super.visitInsn(opcode);
            }
        };
    }

    private static void getStatic(final MethodVisitor mv, final String field, final String desc) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, TARGET_CLASS, field, desc);
    }
}
