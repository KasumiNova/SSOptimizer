package github.kasuminova.ssoptimizer.common.font;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

record FontLayoutTable(int size, boolean antiAlias, int lineHeight, int baseline, Map<Integer, Cell> cells,
                       List<Kerning> kerning, long fingerprint) {
    record Cell(int width, int height, int left, int top, int encodedAdvance) {
        int penAdvance() { return left + encodedAdvance; }
    }
    record Kerning(int first, int second, int amount) { }
    private static final Map<String, Optional<FontLayoutTable>> TABLES = new ConcurrentHashMap<>();

    static FontLayoutTable forPath(String fontPath) {
        return TABLES.computeIfAbsent(fontPath, FontLayoutTable::load).orElse(null);
    }

    private static Optional<FontLayoutTable> load(String fontPath) {
        if (!fontPath.startsWith("graphics/fonts/") || !fontPath.endsWith(".fnt")) return Optional.empty();
        String name = fontPath.substring("graphics/fonts/".length());
        if (name.contains("/")) return Optional.empty();
        String resource = "layout-tables/" + name.substring(0, name.length() - 4) + ".metrics";
        try (InputStream stream = FontLayoutTable.class.getResourceAsStream(resource)) {
            if (stream == null) return Optional.empty();
            byte[] bytes = stream.readAllBytes();
            CRC32 crc = new CRC32();
            crc.update(bytes);
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            int size = 0, lineHeight = 0, baseline = 0;
            boolean aa = false;
            Map<Integer, Cell> cells = new HashMap<>();
            List<Kerning> kerning = new ArrayList<>();
            for (int tag; (tag = in.readUnsignedByte()) != 0; ) {
                switch (tag) {
                    case 1 -> { size = in.readInt(); aa = in.readInt() != 0; }
                    case 2 -> { lineHeight = in.readInt(); baseline = in.readInt(); }
                    case 3 -> cells.put(in.readInt(), new Cell(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt()));
                    case 4 -> kerning.add(new Kerning(in.readInt(), in.readInt(), in.readInt()));
                    default -> throw new IOException("未知字体度量记录 " + tag);
                }
            }
            if (size == 0 || lineHeight <= 0 || cells.isEmpty() || in.available() != 0) throw new IOException("无效布局表");
            return Optional.of(new FontLayoutTable(size, aa, lineHeight, baseline, Map.copyOf(cells), List.copyOf(kerning), crc.getValue()));
        } catch (IOException e) {
            throw new UncheckedIOException("读取字体排版表失败: " + resource, e);
        }
    }
}
