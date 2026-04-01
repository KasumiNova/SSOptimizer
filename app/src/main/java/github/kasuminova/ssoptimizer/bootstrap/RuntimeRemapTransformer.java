package github.kasuminova.ssoptimizer.bootstrap;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * 运行时重映射变换器。
 * <p>
 * 它位于 bootstrap 管线最前面，负责把混淆类字节码转换为 Tiny v2 记录的可读命名，
 * 这样后续 ASM 与 Mixin Patch 就能在统一命名空间里工作。
 */
public final class RuntimeRemapTransformer implements ClassFileTransformer {
    private final RuntimeRemapContext context;

    /**
     * 使用默认 Tiny v2 映射资源创建变换器。
     */
    public RuntimeRemapTransformer() {
        this(RuntimeRemapContext.loadDefault());
    }

    /**
     * 使用指定上下文创建变换器。
     *
     * @param context 运行时重映射上下文
     */
    public RuntimeRemapTransformer(RuntimeRemapContext context) {
        this.context = context;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 若类不在 Tiny v2 映射中或已经是可读命名，则返回 {@code null} 让 JVM 继续使用原始字节码。
     */
    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        return context.remap(className, classfileBuffer);
    }
}