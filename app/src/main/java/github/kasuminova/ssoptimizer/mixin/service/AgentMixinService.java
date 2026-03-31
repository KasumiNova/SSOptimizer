package github.kasuminova.ssoptimizer.mixin.service;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Minimal {@link org.spongepowered.asm.service.IMixinService} for a Java-agent
 * environment. Relies on the system classloader for class discovery and
 * bytecode access.
 */
public final class AgentMixinService extends MixinServiceAbstract
        implements IClassBytecodeProvider, IClassProvider, ITransformerProvider {

    private static final ContainerHandleVirtual PRIMARY_CONTAINER =
            new ContainerHandleVirtual("ssoptimizer");

    private final    Set<String>       transformerExclusions = new HashSet<>();
    private volatile IMixinTransformer transformer;

    // ── IMixinService ───────────────────────────────────────────

    private static void tryRegisterActiveTransformer(IMixinTransformer transformer) {
        try {
            MixinEnvironment.getDefaultEnvironment().setActiveTransformer(transformer);
            MixinEnvironment.getCurrentEnvironment().setActiveTransformer(transformer);
        } catch (Throwable ignored) {
            // Unit tests may instantiate the service without fully bootstrapping
            // the Mixin environment. Runtime initialization still performs the
            // registration path normally after MixinBootstrap.init().
        }
    }

    @Override
    public String getName() {
        return "SSOptimizer/Agent";
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public IClassProvider getClassProvider() {
        return this;
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return this;
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        return this;
    }

    @Override
    public IClassTracker getClassTracker() {
        return null;
    }

    @Override
    public IMixinAuditTrail getAuditTrail() {
        return null;
    }

    @Override
    public Collection<String> getPlatformAgents() {
        return Collections.emptyList();
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        return PRIMARY_CONTAINER;
    }

    // ── IClassProvider ──────────────────────────────────────────

    @Override
    public InputStream getResourceAsStream(String name) {
        return getClass().getClassLoader().getResourceAsStream(name);
    }

    @Override
    public URL[] getClassPath() {
        ClassLoader cl = getClass().getClassLoader();
        if (cl instanceof URLClassLoader ucl) {
            return ucl.getURLs();
        }
        return new URL[0];
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return Class.forName(name, true, getClass().getClassLoader());
    }

    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, getClass().getClassLoader());
    }

    // ── IClassBytecodeProvider ──────────────────────────────────

    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, getClass().getClassLoader());
    }

    @Override
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, true, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers)
            throws ClassNotFoundException, IOException {
        return getClassNode(name, runTransformers, 0);
    }

    // ── ITransformerProvider ────────────────────────────────────

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags)
            throws ClassNotFoundException, IOException {
        String resourcePath = name.replace('.', '/') + ".class";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new ClassNotFoundException(name);
            }
            ClassNode node = new ClassNode();
            new ClassReader(is).accept(node, readerFlags);
            return node;
        }
    }

    @Override
    public Collection<ITransformer> getTransformers() {
        return Collections.emptyList();
    }

    @Override
    public Collection<ITransformer> getDelegatedTransformers() {
        return Collections.emptyList();
    }

    @Override
    public void addTransformerExclusion(String name) {
        transformerExclusions.add(name);
    }

    public synchronized IMixinTransformer getOrCreateTransformer() {
        IMixinTransformer current = transformer;
        if (current != null) {
            return current;
        }

        IMixinTransformerFactory factory = getInternal(IMixinTransformerFactory.class);
        if (factory == null) {
            throw new IllegalStateException("Mixin transformer factory was not offered to AgentMixinService");
        }

        current = factory.createTransformer();
        tryRegisterActiveTransformer(current);
        transformer = current;
        return current;
    }
}
