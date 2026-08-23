package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.asm.loading.AITweaksBootstrapLoaderProcessor;
import github.kasuminova.ssoptimizer.asm.loading.AITweaksCoreLoaderProcessor;
import github.kasuminova.ssoptimizer.asm.loading.AstdTexTrailLedgerProcessor;
import github.kasuminova.ssoptimizer.asm.loading.BoxConfigGuiLedgerProcessor;
import github.kasuminova.ssoptimizer.asm.loading.BoxInstancePoolLedgerProcessor;
import github.kasuminova.ssoptimizer.asm.loading.BoxLegacyNormalMapLedgerProcessor;
import github.kasuminova.ssoptimizer.asm.loading.BoxRenderingBufferLedgerProcessor;
import github.kasuminova.ssoptimizer.asm.loading.BoxShaderCoreLedgerProcessor;
import github.kasuminova.ssoptimizer.asm.loading.BoxTextureUploadLedgerProcessor;
import github.kasuminova.ssoptimizer.asm.loading.LightShaderLedgerProcessor;
import github.kasuminova.ssoptimizer.asm.loading.MociSingularityLedgerProcessor;
import github.kasuminova.ssoptimizer.asm.loading.No101SingularityLedgerProcessor;
import github.kasuminova.ssoptimizer.asm.loading.ParticleEngineVboLedgerProcessor;
import github.kasuminova.ssoptimizer.asm.loading.PublicFboLedgerProcessor;
import github.kasuminova.ssoptimizer.asm.loading.ShaderLibLedgerProcessor;
import github.kasuminova.ssoptimizer.asm.loading.ShipMasteryReflectionLoaderProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.modopt.dcr.DcrBatchSaveSynthProcessor;
import github.kasuminova.ssoptimizer.modopt.dcr.DcrOnGameLoadProcessor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineProcessorRegistrationTest {

    private static Map<String, AsmClassProcessor> collectRegisteredProcessors() {
        Map<String, AsmClassProcessor> processors = new LinkedHashMap<>();
        SSOptimizerCorePlugin.registerAllProcessors(processors::put);
        return processors;
    }

    @Test
    void registersEngineAndExternalModProcessors() {
        Map<String, AsmClassProcessor> processors = collectRegisteredProcessors();

        // 16 个引擎级注册项（含 RESOURCE_LOADER 组合处理器）+ 2 个 DCR 处理器
        // + 14 个 GL 显存账本模组埋点注册项（第一批 5 + upTex/screenRT/vbo 9，
        // ParticleEngine 一个处理器实例占两个目标类键）
        assertEquals(32, processors.size());
        assertTrue(processors.containsKey(GameClassNames.TEXTURE_LOADER));
        assertTrue(processors.containsKey(GameClassNames.COMBAT_STATE));
        assertTrue(processors.containsKey(GameClassNames.RESOURCE_LOADER));
        assertTrue(processors.containsKey(DcrBatchSaveSynthProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(DcrOnGameLoadProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(AITweaksCoreLoaderProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(AITweaksBootstrapLoaderProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(ShipMasteryReflectionLoaderProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(ShaderLibLedgerProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(LightShaderLedgerProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(BoxShaderCoreLedgerProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(BoxRenderingBufferLedgerProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(PublicFboLedgerProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(BoxTextureUploadLedgerProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(BoxLegacyNormalMapLedgerProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(MociSingularityLedgerProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(No101SingularityLedgerProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(AstdTexTrailLedgerProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(BoxConfigGuiLedgerProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(BoxInstancePoolLedgerProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(ParticleEngineVboLedgerProcessor.TARGET_CLASS_ALLOCATOR));
        assertTrue(processors.containsKey(ParticleEngineVboLedgerProcessor.TARGET_CLASS_EMITTER));
    }

    @Test
    void disableSwitchSkipsSingleProcessor() {
        String original = System.getProperty("ssoptimizer.disable.textureloader");
        try {
            System.setProperty("ssoptimizer.disable.textureloader", "true");
            Map<String, AsmClassProcessor> processors = collectRegisteredProcessors();

            assertEquals(31, processors.size());
            assertFalse(processors.containsKey(GameClassNames.TEXTURE_LOADER));
        } finally {
            restoreProperty("ssoptimizer.disable.textureloader", original);
        }
    }

    @Test
    void disableDcrSwitchSkipsAllDcrProcessors() {
        String original = System.getProperty("ssoptimizer.disable.dcr");
        try {
            System.setProperty("ssoptimizer.disable.dcr", "true");
            Map<String, AsmClassProcessor> processors = collectRegisteredProcessors();

            assertEquals(30, processors.size());
            assertFalse(processors.containsKey(DcrBatchSaveSynthProcessor.TARGET_CLASS));
        } finally {
            restoreProperty("ssoptimizer.disable.dcr", original);
        }
    }

    @Test
    void disableGlLedgerSwitchSkipsAllLedgerProcessors() {
        String original = System.getProperty("ssoptimizer.disable.glledger");
        try {
            System.setProperty("ssoptimizer.disable.glledger", "true");
            Map<String, AsmClassProcessor> processors = collectRegisteredProcessors();

            assertEquals(18, processors.size());
            assertFalse(processors.containsKey(ShaderLibLedgerProcessor.TARGET_CLASS));
            assertFalse(processors.containsKey(PublicFboLedgerProcessor.TARGET_CLASS));
        } finally {
            restoreProperty("ssoptimizer.disable.glledger", original);
        }
    }

    private static void restoreProperty(String key, String original) {
        if (original == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, original);
        }
    }
}
