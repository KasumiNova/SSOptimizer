package github.kasuminova.ssoptimizer.mapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 映射表导出格式测试。
 * <p>
 * 该测试锁定 markdown、CSV 与控制台表格三种输出格式的稳定性，避免维护映射时
 * 因格式漂移导致文档或自动化校验失效。
 */
class MappingTableExporterTest {

    private static final MappingRepository FIXTURE_REPOSITORY = new MappingRepository() {
        private final java.util.List<MappingEntry> entries = java.util.List.of(
                MappingEntry.classEntry("com/fs/graphics/TextureLoader", "com/fs/graphics/TextureLoader"),
                MappingEntry.fieldEntry("com/fs/graphics/TextureLoader", "com/fs/graphics/TextureLoader", "a", "cacheSize", "I"),
                MappingEntry.methodEntry("com/fs/graphics/TextureLoader", "com/fs/graphics/TextureLoader", "b", "reloadCache", "(I)V")
        );

        @Override
        public java.util.List<MappingEntry> entries() {
            return entries;
        }

        @Override
        public java.util.Optional<MappingEntry> findClassByObfuscatedName(String obfuscatedName) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<MappingEntry> findClassByNamedName(String namedName) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<MappingEntry> findFieldByObfuscatedName(String ownerObfuscatedName, String fieldName) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<MappingEntry> findFieldByNamedName(String ownerNamedName, String fieldName) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<MappingEntry> findMethodByObfuscatedName(String ownerObfuscatedName, String methodName, String descriptor) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<MappingEntry> findMethodByNamedName(String ownerNamedName, String methodName, String descriptor) {
            return java.util.Optional.empty();
        }
    };

    @Test
    void exportsStableMarkdownCsvAndTableFormats() {
        MappingTableExporter exporter = new MappingTableExporter(FIXTURE_REPOSITORY);

        assertEquals(String.join("\n",
            "# SSOptimizer Mapping Table",
            "",
            "| kind | obfuscated | named | descriptor |",
            "|---|---|---|---|",
            "| class | com/fs/graphics/TextureLoader | com/fs/graphics/TextureLoader | |",
            "| field | com/fs/graphics/TextureLoader#a | cacheSize | I |",
            "| method | com/fs/graphics/TextureLoader#b | reloadCache | (I)V |") + "\n",
            exporter.exportMarkdown());

        assertEquals(String.join("\n",
            "kind,obfuscated,named,descriptor",
            "class,com/fs/graphics/TextureLoader,com/fs/graphics/TextureLoader,",
            "field,com/fs/graphics/TextureLoader#a,cacheSize,I",
            "method,com/fs/graphics/TextureLoader#b,reloadCache,(I)V") + "\n",
            exporter.exportCsv());

        String table = exporter.exportTable();
        assertTrue(table.startsWith("SSOptimizer Mapping Table\n"));
        assertTrue(table.contains("kind"));
        assertTrue(table.contains("obfuscated"));
        assertTrue(table.contains("named"));
        assertTrue(table.contains("descriptor"));
        assertTrue(table.contains("class  com/fs/graphics/TextureLoader"));
        assertTrue(table.contains("field  com/fs/graphics/TextureLoader#a"));
        assertTrue(table.contains("method com/fs/graphics/TextureLoader#b"));
        assertTrue(table.contains("com/fs/graphics/TextureLoader"));
        assertTrue(table.contains("cacheSize"));
        assertTrue(table.contains("reloadCache"));
        assertTrue(table.contains("(I)V"));
    }

    @Test
    void exportTinyRoundTripsEntriesAndComments() {
        java.util.List<MappingEntry> entries = java.util.List.of(
                MappingEntry.classEntry("com/fs/example/A", "com/fs/example/Alpha")
                        .withComment("来源: javap取证; 置信度: 高"),
                MappingEntry.fieldEntry("com/fs/example/A", "com/fs/example/Alpha", "a", "alphaField", "I")
                        .withComment("字段注释"),
                MappingEntry.methodEntry("com/fs/example/A", "com/fs/example/Alpha", "b", "doWork", "(I)V")
        );

        String tiny = new MappingTableExporter(TinyV2MappingRepository.of(entries)).exportTiny();
        TinyV2MappingRepository reparsed = TinyV2MappingRepository.loadFromResource(
                new java.io.ByteArrayInputStream(tiny.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "memory:roundtrip.tiny");

        assertEquals(entries.size(), reparsed.entries().size());
        for (int i = 0; i < entries.size(); i++) {
            MappingEntry expected = entries.get(i);
            MappingEntry actual = reparsed.entries().get(i);
            assertEquals(expected.kind(), actual.kind());
            assertEquals(expected.obfuscatedName(), actual.obfuscatedName());
            assertEquals(expected.namedName(), actual.namedName());
            assertEquals(expected.descriptor(), actual.descriptor());
            assertEquals(expected.comment(), actual.comment());
        }
    }
}
