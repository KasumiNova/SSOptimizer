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
        assertEquals(18.5f, vertices[1], 0.0001f);
        assertEquals(13.0f, vertices[4], 0.0001f);
        assertEquals(18.5f, vertices[5], 0.0001f);
        assertEquals(22.0f, vertices[8], 0.0001f);
        assertEquals(21.5f, vertices[11], 0.0001f);
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
    void computeGlowAlphaTracksFlameLevelInsteadOfUsingConstantFloor() {
        float lowFlameAlpha = EngineRenderHelper.computeGlowAlpha(0.0f, 0.2f, 0.5f, 1.0f);
        float hotFlameAlpha = EngineRenderHelper.computeGlowAlpha(0.0f, 0.9f, 1.0f, 1.0f);

        assertEquals(0.0f, lowFlameAlpha, 0.0001f,
                "Low flame levels should not force the glow sprite to a constant visible alpha");
        assertEquals(0.375f, hotFlameAlpha, 0.0001f,
                "Glow alpha should follow the original flameLevel-0.4 ramp before edgeAlpha clamping");
    }
}