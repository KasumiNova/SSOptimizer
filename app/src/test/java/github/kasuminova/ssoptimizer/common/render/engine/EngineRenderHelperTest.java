package github.kasuminova.ssoptimizer.common.render.engine;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class EngineRenderHelperTest {
    @Test
    void enablesExactAlphaNativePathForTranslucentEnginePasses() {
        assertTrue(EngineRenderHelper.requiresExactAlphaNativePath(0.75f));
        assertFalse(EngineRenderHelper.requiresExactAlphaNativePath(1.0f));
    }

    @Test
    void declaresBatchedStripNativeEntry() throws IOException {
        try (InputStream in = getClass().getClassLoader()
                                        .getResourceAsStream("github/kasuminova/ssoptimizer/common/render/engine/EngineRenderHelper.class")) {
            assertNotNull(in, "EngineRenderHelper bytecode should be available on the test classpath");

            boolean[] foundNative = {false};
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if ("nativeRenderEngineStripBatch".equals(name)
                            && "(FFFFFIIFFFFFFIIIFZ)V".equals(descriptor)
                            && (access & Opcodes.ACC_NATIVE) != 0) {
                        foundNative[0] = true;
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            assertTrue(foundNative[0],
                    "EngineRenderHelper should keep the batched strip path as a native entry");
        }
    }

    @Test
    void computeStripVerticesMatchesExpectedZeroRotationLayout() {
        // 原版公式：scaleX = 0.5 + 0.5*(pass+1)/passCount = 1.0，scaleY = (passCount-pass)/passCount = 0.25
        float[] vertices = EngineRenderHelper.computeStripVertices(
                10.0f, 20.0f,
                0.0f,
                0.0f,
                0.0f,
                4.0f,
                3,
                2.0f,
                8.0f,
                4.0f
        );

        assertEquals(10.0f, vertices[0], 0.0001f);
        assertEquals(19.5f, vertices[1], 0.0001f);
        assertEquals(12.0f, vertices[4], 0.0001f);
        assertEquals(19.5f, vertices[5], 0.0001f);
        assertEquals(18.0f, vertices[8], 0.0001f);
        assertEquals(20.5f, vertices[11], 0.0001f);
    }

    @Test
    void computeCoreVerticesAppliesCoreScaleBeforeWorldTransform() {
        float[] vertices = EngineRenderHelper.computeCoreVertices(
                5.0f, -2.0f,
                0.0f,
                0.0f,
                0.0f,
                10.0f,
                4.0f
        );

        assertEquals(5.0f, vertices[0], 0.0001f);
        assertEquals(-4.0f, vertices[1], 0.0001f);
        assertEquals(14.0f, vertices[4], 0.0001f);
        assertEquals(0.0f, vertices[7], 0.0001f);
    }

    @Test
    void computeGlowAlphaUsesVanillaConstantFlameFloor() {
        // 原版在 glow 前将火焰强度重置为 1.0F：Math.max(base, 1.0F - 0.4F) * 0.75F * alphaScale
        float baseAlpha = EngineRenderHelper.computeGlowAlpha(0.0f, 0.5f, 1.0f);
        float hotBaseAlpha = EngineRenderHelper.computeGlowAlpha(0.9f, 1.0f, 1.0f);

        assertEquals(0.45f, baseAlpha, 0.0001f,
                "Glow alpha should use the vanilla constant 0.6 floor scaled by 0.75");
        assertEquals(0.675f, hotBaseAlpha, 0.0001f,
                "Glow alpha should follow max(base, 0.6) * 0.75 before edgeAlpha clamping");
    }
}