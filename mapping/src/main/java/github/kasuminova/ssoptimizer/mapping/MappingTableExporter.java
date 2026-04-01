package github.kasuminova.ssoptimizer.mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 映射表导出器。
 * <p>
 * 将仓库中的 Tiny v2 数据转成 markdown、CSV 以及控制台表格，便于审查与维护。
 */
public final class MappingTableExporter {
    private final MappingRepository repository;

    /**
     * 创建导出器。
     *
     * @param repository 映射仓库
     */
    public MappingTableExporter(MappingRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * 导出 markdown 表格。
     *
     * @return markdown 文本
     */
    public String exportMarkdown() {
        StringBuilder builder = new StringBuilder();
        builder.append("# SSOptimizer Mapping Table\n\n");
        builder.append("| kind | obfuscated | named | descriptor |\n");
        builder.append("|---|---|---|---|\n");
        for (Row row : rows()) {
            builder.append("| ")
                    .append(row.kind)
                    .append(" | ")
                    .append(row.obfuscated)
                    .append(" | ")
                    .append(row.named)
                    .append(row.descriptor.isEmpty() ? " | |\n" : " | ")
                    .append(row.descriptor)
                    .append(row.descriptor.isEmpty() ? "" : " |\n");
        }
        return builder.toString();
    }

    /**
     * 导出 CSV 表格。
     *
     * @return CSV 文本
     */
    public String exportCsv() {
        StringBuilder builder = new StringBuilder();
        builder.append("kind,obfuscated,named,descriptor\n");
        for (Row row : rows()) {
            builder.append(row.kind).append(',')
                    .append(row.obfuscated).append(',')
                    .append(row.named).append(',')
                    .append(row.descriptor)
                    .append('\n');
        }
        return builder.toString();
    }

    /**
     * 导出固定宽度的控制台表格。
     *
     * @return 对齐后的文本表格
     */
    public String exportTable() {
        List<Row> rows = rows();
        int kindWidth = Math.max("kind".length(), rows.stream().mapToInt(row -> row.kind.length()).max().orElse(0));
        int obfuscatedWidth = Math.max("obfuscated".length(), rows.stream().mapToInt(row -> row.obfuscated.length()).max().orElse(0));
        int namedWidth = Math.max("named".length(), rows.stream().mapToInt(row -> row.named.length()).max().orElse(0));

        StringBuilder builder = new StringBuilder();
        builder.append("SSOptimizer Mapping Table\n");
        builder.append(String.format(Locale.ROOT, "%-" + kindWidth + "s %-" + obfuscatedWidth + "s %-" + namedWidth + "s %s\n",
                "kind", "obfuscated", "named", "descriptor"));
        for (Row row : rows) {
            if (row.descriptor.isEmpty()) {
                builder.append(String.format(Locale.ROOT, "%-" + kindWidth + "s %-" + obfuscatedWidth + "s %-" + namedWidth + "s\n",
                        row.kind, row.obfuscated, row.named));
            } else {
                builder.append(String.format(Locale.ROOT, "%-" + kindWidth + "s %-" + obfuscatedWidth + "s %-" + namedWidth + "s %s\n",
                        row.kind, row.obfuscated, row.named, row.descriptor));
            }
        }
        return builder.toString();
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (MappingEntry entry : repository.entries()) {
            rows.add(new Row(
                    entry.kind().name().toLowerCase(Locale.ROOT),
                    toObfuscatedText(entry),
                    toNamedText(entry),
                    entry.descriptor() == null ? "" : entry.descriptor()));
        }
        return rows;
    }

    private static String toObfuscatedText(MappingEntry entry) {
        if (entry.isClass()) {
            return entry.obfuscatedName();
        }
        return entry.ownerObfuscatedName() + '#' + entry.obfuscatedName();
    }

    private static String toNamedText(MappingEntry entry) {
        if (entry.isClass()) {
            return entry.namedName();
        }
        return entry.namedName();
    }

    private record Row(String kind, String obfuscated, String named, String descriptor) {
    }
}