package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.asm.loading.AITweaksBootstrapLoaderProcessor;
import github.kasuminova.ssoptimizer.asm.loading.AITweaksCoreLoaderProcessor;
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
        // − 3 个 IME 处理器（ebd2ee9 起 lwjgl 目标迁移至 NanoForge SystemAsmBridge，
        // 不再出现在 Launch 域注册表）
        // （GL 显存账本模组埋点已整体移除：BoxUtil 1.6.0 移除 _INTERNAL_FORMAT/FORMAT
        // 静态字段导致注入字节码运行期 NoSuchFieldError，该体系仅服务开发期显存观测，
        // 不应承担生产环境模组兼容性风险）
        assertEquals(15, processors.size());
        assertTrue(processors.containsKey(GameClassNames.TEXTURE_LOADER));
        assertTrue(processors.containsKey(GameClassNames.COMBAT_STATE));
        assertTrue(processors.containsKey(GameClassNames.RESOURCE_LOADER));
        assertTrue(processors.containsKey(DcrBatchSaveSynthProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(DcrOnGameLoadProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(AITweaksCoreLoaderProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(AITweaksBootstrapLoaderProcessor.TARGET_CLASS));
        assertTrue(processors.containsKey(ShipMasteryReflectionLoaderProcessor.TARGET_CLASS));
    }

    @Test
    void disableSwitchSkipsSingleProcessor() {
        String original = System.getProperty("ssoptimizer.disable.textureloader");
        try {
            System.setProperty("ssoptimizer.disable.textureloader", "true");
            Map<String, AsmClassProcessor> processors = collectRegisteredProcessors();

            assertEquals(14, processors.size());
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

            assertEquals(13, processors.size());
            assertFalse(processors.containsKey(DcrBatchSaveSynthProcessor.TARGET_CLASS));
        } finally {
            restoreProperty("ssoptimizer.disable.dcr", original);
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
