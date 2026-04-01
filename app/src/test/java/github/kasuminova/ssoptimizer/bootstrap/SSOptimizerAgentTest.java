package github.kasuminova.ssoptimizer.bootstrap;

import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SSOptimizerAgentTest {
    private static int indexOf(List<ClassFileTransformer> transformers, Class<? extends ClassFileTransformer> type) {
        for (int i = 0; i < transformers.size(); i++) {
            if (type.isInstance(transformers.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void premainMethodHasCorrectSignature() throws Exception {
        Method premain = SSOptimizerAgent.class.getMethod("premain", String.class, Instrumentation.class);
        assertTrue(Modifier.isPublic(premain.getModifiers()));
        assertTrue(Modifier.isStatic(premain.getModifiers()));
        assertSame(void.class, premain.getReturnType());
    }

    @Test
    void premainStateIsEmptyBeforeAgentLoad() {
        assertNull(SSOptimizerAgent.getInstrumentation());
        assertNull(SSOptimizerAgent.getWeaverTransformer());
    }

    @Test
    void runtimeRemapTransformerPrecedesPatchPipeline() {
        List<ClassFileTransformer> transformers = SSOptimizerAgent.createBootstrapTransformers();

        assertFalse(transformers.isEmpty());
        assertInstanceOf(RuntimeRemapTransformer.class, transformers.get(0));
        int remapIndex = indexOf(transformers, RuntimeRemapTransformer.class);
        int patchIndex = indexOf(transformers, HybridWeaverTransformer.class);

        assertTrue(remapIndex >= 0, "应该注册运行时重映射变换器");
        assertTrue(patchIndex >= 0, "应该注册 ASM/Mixin 修复管线");
        assertTrue(remapIndex < patchIndex, "remap 必须发生在 patch 之前");
    }
}
