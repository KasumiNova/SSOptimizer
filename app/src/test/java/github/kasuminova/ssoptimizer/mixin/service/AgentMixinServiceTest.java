package github.kasuminova.ssoptimizer.mixin.service;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.mixin.transformer.ext.IExtensionRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class AgentMixinServiceTest {
    @Test
    void createsAndCachesTransformerFromOfferedFactory() {
        AgentMixinService service = new AgentMixinService();
        FakeTransformer transformer = new FakeTransformer();
        service.offer(new FakeFactory(transformer));

        IMixinTransformer created = service.getOrCreateTransformer();

        assertNotNull(created);
        assertSame(transformer, created);
        assertSame(created, service.getOrCreateTransformer());
    }

    private record FakeFactory(IMixinTransformer transformer) implements IMixinTransformerFactory {

        @Override
        public IMixinTransformer createTransformer() {
            return transformer;
        }
    }

    private static final class FakeTransformer implements IMixinTransformer {
        @Override
        public void audit(org.spongepowered.asm.mixin.MixinEnvironment environment) {
        }

        @Override
        public List<String> reload(String mixinClass, ClassNode classNode) {
            return List.of();
        }

        @Override
        public boolean computeFramesForClass(org.spongepowered.asm.mixin.MixinEnvironment environment, String name, ClassNode classNode) {
            return false;
        }

        @Override
        public byte[] transformClassBytes(String name, String transformedName, byte[] basicClass) {
            return basicClass;
        }

        @Override
        public byte[] transformClass(org.spongepowered.asm.mixin.MixinEnvironment environment, String name, byte[] classBytes) {
            return classBytes;
        }

        @Override
        public boolean transformClass(org.spongepowered.asm.mixin.MixinEnvironment environment, String name, ClassNode classNode) {
            return false;
        }

        @Override
        public byte[] generateClass(org.spongepowered.asm.mixin.MixinEnvironment environment, String name) {
            return new byte[0];
        }

        @Override
        public boolean generateClass(org.spongepowered.asm.mixin.MixinEnvironment environment, String name, ClassNode classNode) {
            return false;
        }

        @Override
        public IExtensionRegistry getExtensions() {
            return null;
        }
    }
}