package github.kasuminova.ssoptimizer.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuntimeRemapContext} 全量 deobf 开关行为测试。
 * <p>
 * 测试资源侧放置了小型的 {@code /mappings/ssoptimizer-<platform>-full.tiny.gz} fixture，
 * 其中包含小表（35 类桥接表）不存在的 {@code com/fs/example/FullOnly} 条目，
 * 以此区分 {@code loadDefault()} 在开关开/关时实际加载的是全量表还是小表。
 */
class RuntimeRemapContextFullDeobfTest {

    @Test
    void fullDeobfSwitchLoadsFullMappingTable() {
        System.setProperty(RuntimeRemapContext.FULL_DEOBF_PROPERTY, "true");
        try {
            assertTrue(RuntimeRemapContext.isFullDeobfEnabled());

            RuntimeRemapContext context = RuntimeRemapContext.loadDefault();
            assertEquals("com/fs/example/FullOnlyNamed",
                    context.translateClassName("com/fs/example/FullOnly"),
                    "开关开启时应加载全量表（fixture 独有条目可翻译）");
        } finally {
            System.clearProperty(RuntimeRemapContext.FULL_DEOBF_PROPERTY);
        }
    }

    @Test
    void defaultModeKeepsSmallMappingTable() {
        System.clearProperty(RuntimeRemapContext.FULL_DEOBF_PROPERTY);
        assertFalse(RuntimeRemapContext.isFullDeobfEnabled());

        RuntimeRemapContext context = RuntimeRemapContext.loadDefault();
        assertEquals("com/fs/example/FullOnly",
                context.translateClassName("com/fs/example/FullOnly"),
                "开关关闭时应维持 35 类小表，fixture 条目不可翻译");
        assertEquals("com/fs/graphics/TextureLoader",
                context.translateClassName("com/fs/graphics/TextureLoader"),
                "开关关闭时小表桥接映射仍应可用");
    }
}
