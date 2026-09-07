package github.kasuminova.ssoptimizer.common.debug;

import com.sun.tools.attach.VirtualMachine;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * L2 方法体 hotswap 支撑：运行期自 attach 与 redefineClasses。
 *
 * <p>机制：生成仅含 {@link HotswapAgent} 的微型 agent jar →
 * {@code VirtualMachine.attach(自身)} → {@code loadAgent} → 取回
 * {@link Instrumentation}。全程运行期完成，<b>不需要 {@code -javaagent} 启动参数</b>，
 * 但需要 {@code -Djdk.attach.allowAttachSelf=true}（JDK 25 在启动期读取该属性，
 * 运行期 setProperty 无效——zulu25 实测）。</p>
 *
 * <p>限制（HotSpot 类重定义语义）：仅方法体可替换，不可增删成员、改签名；
 * 已建实例的字段布局不变。目标类必须已被加载。</p>
 */
public final class HotswapSupport {
    private static final Logger LOGGER = Logger.getLogger(HotswapSupport.class);

    private static final String AGENT_CLASS = "github.kasuminova.ssoptimizer.common.debug.HotswapAgent";
    private static final String AGENT_CLASS_RESOURCE = "github/kasuminova/ssoptimizer/common/debug/HotswapAgent.class";
    private static final String AGENT_JAR_NAME = "hotswap-agent.jar";

    private static volatile Instrumentation instrumentation;

    private HotswapSupport() {
    }

    /**
     * 确保 Instrumentation 就绪（幂等）：未 attach 时执行自 attach 全流程。
     *
     * @param outputDir 调试输出目录（agent jar 物化位置）
     * @return Instrumentation 实例
     * @throws IllegalStateException 自 attach 被禁用或 agent 加载失败
     */
    public static synchronized Instrumentation ensureAttached(final Path outputDir) {
        if (instrumentation != null) {
            return instrumentation;
        }
        if (!Boolean.getBoolean("jdk.attach.allowAttachSelf")) {
            throw new IllegalStateException("[SSOptimizer] hotswap requires -Djdk.attach.allowAttachSelf=true"
                    + " (JDK reads this property at startup; runtime setProperty has no effect)");
        }
        final Path agentJar = materializeAgentJar(outputDir);
        final long pid = ProcessHandle.current().pid();
        try {
            final VirtualMachine vm = VirtualMachine.attach(String.valueOf(pid));
            try {
                vm.loadAgent(agentJar.toAbsolutePath().toString());
            } finally {
                vm.detach();
            }
            // Instrumentation 实例只能经 agent 回调写入系统类加载器域的静态字段，
            // 跨域取回无其他入口点（类加载器隔离是 agent 机制的固有语义），此处反射不可避免
            final Class<?> agentClass = Class.forName(AGENT_CLASS, false, ClassLoader.getSystemClassLoader());
            final Object inst = agentClass.getField("INST").get(null);
            if (!(inst instanceof Instrumentation)) {
                throw new IllegalStateException("[SSOptimizer] hotswap agent did not report Instrumentation");
            }
            instrumentation = (Instrumentation) inst;
            LOGGER.info("[SSOptimizer] hotswap agent attached (redefineClasses supported: "
                    + instrumentation.isRedefineClassesSupported() + ")");
            return instrumentation;
        } catch (final ReflectiveOperationException | IOException
                 | com.sun.tools.attach.AttachNotSupportedException
                 | com.sun.tools.attach.AgentLoadException
                 | com.sun.tools.attach.AgentInitializationException e) {
            throw new IllegalStateException("[SSOptimizer] hotswap self-attach failed", e);
        }
    }

    /**
     * 方法体级重定义已加载类。
     *
     * @param className 目标类全限定名（必须已加载）
     * @param classBytes 新类字节（与已加载版本同结构，仅方法体可不同）
     * @param outputDir 调试输出目录（首次 attach 时物化 agent jar）
     */
    public static void redefine(final String className, final byte[] classBytes, final Path outputDir) {
        final Instrumentation inst = ensureAttached(outputDir);
        final Class<?> target;
        try {
            target = Class.forName(className, false, DebugContextImpl.class.getClassLoader());
        } catch (final ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    "[SSOptimizer] hotswap target class not loaded: " + className, e);
        }
        try {
            inst.redefineClasses(new ClassDefinition(target, classBytes));
        } catch (final UnsupportedOperationException | LinkageError e) {
            throw new IllegalStateException("[SSOptimizer] hotswap redefine failed for " + className
                    + " (method-body only: no member add/remove, no signature change)", e);
        } catch (final Exception e) {
            throw new IllegalStateException("[SSOptimizer] hotswap redefine failed for " + className, e);
        }
        LOGGER.info("[SSOptimizer] hotswapped " + className);
    }

    private static Path materializeAgentJar(final Path outputDir) {
        final Path agentJar = outputDir.resolve(AGENT_JAR_NAME);
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Agent-Class", AGENT_CLASS);
        manifest.getMainAttributes().putValue("Can-Redefine-Classes", "true");
        try {
            Files.createDirectories(outputDir);
            final byte[] classBytes;
            try (InputStream in = HotswapSupport.class.getClassLoader()
                    .getResourceAsStream(AGENT_CLASS_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(
                            "[SSOptimizer] hotswap agent class resource missing: " + AGENT_CLASS_RESOURCE);
                }
                classBytes = in.readAllBytes();
            }
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(agentJar), manifest)) {
                jar.putNextEntry(new JarEntry(AGENT_CLASS_RESOURCE));
                jar.write(classBytes);
                jar.closeEntry();
            }
            return agentJar;
        } catch (final IOException e) {
            throw new IllegalStateException("[SSOptimizer] failed to materialize hotswap agent jar", e);
        }
    }
}
