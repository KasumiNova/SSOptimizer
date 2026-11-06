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

/** 自 attach 要求启动时设置 jdk.attach.allowAttachSelf=true；仅支持已加载类的方法体重定义，不允许结构变更。 */
public final class HotswapSupport {
    private static final Logger LOGGER = Logger.getLogger(HotswapSupport.class);

    private static final String AGENT_CLASS = "github.kasuminova.ssoptimizer.common.debug.HotswapAgent";
    private static final String AGENT_CLASS_RESOURCE = "github/kasuminova/ssoptimizer/common/debug/HotswapAgent.class";
    private static final String AGENT_JAR_NAME = "hotswap-agent.jar";

    private static volatile Instrumentation instrumentation;

    private HotswapSupport() {
    }

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
            // Instrumentation 仅由系统类加载器域的 agent 回调提供，跨域取回该静态字段必须使用反射。
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
