package github.kasuminova.ssoptimizer.loading;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class TextureCompositionReport {
    private static final DateTimeFormatter TIMESTAMP_FORMAT          =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final long              HOT_BIND_WINDOW_MILLIS    = 5_000L;
    private static final long              WARM_BIND_WINDOW_MILLIS   = 15_000L;
    private static final long              COLD_BIND_WINDOW_MILLIS   = 30_000L;
    private static final long              HIGH_BIND_COUNT           = 128L;
    private static final long              ACTIVE_RUNTIME_BIND_COUNT = 8L;

    private TextureCompositionReport() {
    }

    static String render(final List<TextureEntry> entries,
                         final Instant generatedAt) {
        final List<TextureEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingLong(TextureEntry::estimatedGpuBytes).reversed()
                              .thenComparing(TextureEntry::resourcePath));

        final Map<String, GroupSummary> groups = new TreeMap<>();
        final Map<String, RetentionSummary> retention = new TreeMap<>();
        final Map<TextureEntry, RetentionAssessment> assessments = new HashMap<>();
        long totalEstimatedGpuBytes = 0L;
        long residentEstimatedGpuBytes = 0L;
        long evictableResidentEstimatedGpuBytes = 0L;
        int residentCount = 0;
        int pendingCount = 0;

        for (TextureEntry entry : sorted) {
            totalEstimatedGpuBytes += entry.estimatedGpuBytes();
            if ("resident".equals(entry.state())) {
                residentCount++;
                residentEstimatedGpuBytes += entry.estimatedGpuBytes();
                if (entry.evictable()) {
                    evictableResidentEstimatedGpuBytes += entry.estimatedGpuBytes();
                }
            } else {
                pendingCount++;
            }

            groups.computeIfAbsent(groupKey(entry.resourcePath()), ignored -> new GroupSummary())
                  .accumulate(entry);

            final RetentionAssessment assessment = classifyRetention(entry);
            assessments.put(entry, assessment);
            retention.computeIfAbsent(assessment.advice(), ignored -> new RetentionSummary())
                     .accumulate(entry);
        }

        final StringBuilder out = new StringBuilder(Math.max(8_192, sorted.size() * 220));
        out.append("# SSOptimizer texture composition report\n");
        out.append("# generated_at\t").append(TIMESTAMP_FORMAT.format(generatedAt)).append('\n');
        out.append('\n');

        out.append("[summary]\n");
        out.append("metric\tvalue\n");
        out.append("tracked_textures\t").append(sorted.size()).append('\n');
        out.append("resident_textures\t").append(residentCount).append('\n');
        out.append("non_resident_textures\t").append(pendingCount).append('\n');
        out.append("tracked_estimated_gpu_bytes\t").append(totalEstimatedGpuBytes).append('\n');
        out.append("resident_estimated_gpu_bytes\t").append(residentEstimatedGpuBytes).append('\n');
        out.append("evictable_resident_estimated_gpu_bytes\t").append(evictableResidentEstimatedGpuBytes).append('\n');
        out.append('\n');

        out.append("[retention_summary]\n");
        out.append("advice\tcount\tresident_count\testimated_gpu_bytes\tresident_gpu_bytes\n");
        for (Map.Entry<String, RetentionSummary> retained : retention.entrySet()) {
            final RetentionSummary summary = retained.getValue();
            out.append(retained.getKey()).append('\t')
               .append(summary.count).append('\t')
               .append(summary.residentCount).append('\t')
               .append(summary.estimatedGpuBytes).append('\t')
               .append(summary.residentGpuBytes).append('\n');
        }
        out.append('\n');

        out.append("[group_summary]\n");
        out.append("group\tcount\tresident_count\tnon_resident_count\testimated_gpu_bytes\tresident_gpu_bytes\tevictable_resident_gpu_bytes\n");
        for (Map.Entry<String, GroupSummary> grouped : groups.entrySet()) {
            final GroupSummary summary = grouped.getValue();
            out.append(grouped.getKey()).append('\t')
               .append(summary.count).append('\t')
               .append(summary.residentCount).append('\t')
               .append(summary.nonResidentCount).append('\t')
               .append(summary.estimatedGpuBytes).append('\t')
               .append(summary.residentGpuBytes).append('\t')
               .append(summary.evictableResidentGpuBytes).append('\n');
        }
        out.append('\n');

        out.append("[texture_details]\n");
        out.append("retention_advice\tretention_reason\tstate\tevictable\tbind_count\tlast_bind_ago_ms\testimated_gpu_bytes\timage_width\timage_height\ttexture_width\ttexture_height\ttexture_id\tresource_path\tsource_hash\n");
        for (TextureEntry entry : sorted) {
            final RetentionAssessment assessment = assessments.get(entry);
            out.append(assessment.advice()).append('\t')
               .append(sanitize(assessment.reason())).append('\t')
               .append(entry.state()).append('\t')
               .append(entry.evictable()).append('\t')
               .append(entry.bindCount()).append('\t')
               .append(entry.lastBindAgoMillis()).append('\t')
               .append(entry.estimatedGpuBytes()).append('\t')
               .append(entry.imageWidth()).append('\t')
               .append(entry.imageHeight()).append('\t')
               .append(entry.textureWidth()).append('\t')
               .append(entry.textureHeight()).append('\t')
               .append(entry.textureId()).append('\t')
               .append(sanitize(entry.resourcePath())).append('\t')
               .append(entry.sourceHash()).append('\n');
        }

        return out.toString();
    }

    static RetentionAssessment classifyRetention(final TextureEntry entry) {
        if (entry == null) {
            return new RetentionAssessment("not_needed_now", "missing texture entry");
        }

        final String resourcePath = entry.resourcePath() == null ? "" : entry.resourcePath();
        if (!"resident".equals(entry.state())) {
            return switch (entry.state()) {
                case "deferred-awaiting-first-bind" ->
                        new RetentionAssessment("not_needed_now", "metadata-only preload; upload only after first bind");
                case "evicted-awaiting-reload" -> new RetentionAssessment("not_needed_now", "already evicted from VRAM and reloads on demand");
                default -> new RetentionAssessment("not_needed_now", "not resident at capture time");
            };
        }

        if (entry.bindCount() >= HIGH_BIND_COUNT) {
            return new RetentionAssessment("required_now", "very high bind count during capture window");
        }
        if (entry.lastBindAgoMillis() <= HOT_BIND_WINDOW_MILLIS) {
            return new RetentionAssessment("required_now", "bound within the last 5 seconds");
        }
        if (isActiveRuntimePath(resourcePath)
                && (entry.bindCount() >= ACTIVE_RUNTIME_BIND_COUNT || entry.lastBindAgoMillis() <= WARM_BIND_WINDOW_MILLIS)) {
            return new RetentionAssessment("required_now", "active runtime asset group still being touched");
        }
        if (isPreloadHeavyPath(resourcePath) && entry.bindCount() <= 1L) {
            return new RetentionAssessment("optional_resident", "likely startup/menu/codex preload rather than live hotspot");
        }
        if (entry.bindCount() == 0L && entry.lastBindAgoMillis() >= COLD_BIND_WINDOW_MILLIS) {
            return new RetentionAssessment("optional_resident", "resident but never rebound after startup");
        }
        if (entry.bindCount() <= 2L && entry.lastBindAgoMillis() >= WARM_BIND_WINDOW_MILLIS) {
            return new RetentionAssessment("optional_resident", "cold resident candidate with low reuse");
        }
        return new RetentionAssessment("optional_resident", "resident but not currently proven hot");
    }

    private static String groupKey(final String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return "(unknown)";
        }
        final String normalized = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        final String[] segments = normalized.split("/");
        if (segments.length >= 2) {
            return segments[0] + '/' + segments[1];
        }
        return normalized;
    }

    private static String sanitize(final String text) {
        return text == null ? "" : text.replace('\t', ' ').replace('\n', ' ');
    }

    private static boolean isActiveRuntimePath(final String resourcePath) {
        return resourcePath.startsWith("graphics/ships/")
                || resourcePath.startsWith("graphics/weapons/")
                || resourcePath.startsWith("graphics/fx/")
                || resourcePath.startsWith("graphics/shaders/")
                || resourcePath.startsWith("graphics/damage/")
                || resourcePath.startsWith("graphics/debris/")
                || resourcePath.startsWith("graphics/missiles/")
                || resourcePath.startsWith("graphics/stations/")
                || resourcePath.startsWith("graphics/material/")
                || resourcePath.startsWith("graphics/normal/")
                || resourcePath.startsWith("graphics/surface/")
                || resourcePath.startsWith("graphics/maps/")
                || resourcePath.startsWith("graphics/backgrounds/background");
    }

    private static boolean isPreloadHeavyPath(final String resourcePath) {
        return resourcePath.startsWith("graphics/portraits/")
                || resourcePath.startsWith("graphics/illustrations/")
                || resourcePath.startsWith("graphics/intel/")
                || resourcePath.startsWith("graphics/planets/")
                || resourcePath.startsWith("graphics/planet/")
                || resourcePath.startsWith("graphics/backgrounds/")
                || resourcePath.startsWith("graphics/icon/characters/")
                || resourcePath.startsWith("graphics/icon/");
    }

    record TextureEntry(String resourcePath,
                        String state,
                        boolean evictable,
                        long bindCount,
                        long lastBindAgoMillis,
                        int imageWidth,
                        int imageHeight,
                        int textureWidth,
                        int textureHeight,
                        long estimatedGpuBytes,
                        int textureId,
                        String sourceHash) {
    }

    record RetentionAssessment(String advice,
                               String reason) {
    }

    private static final class GroupSummary {
        private int  count;
        private int  residentCount;
        private int  nonResidentCount;
        private long estimatedGpuBytes;
        private long residentGpuBytes;
        private long evictableResidentGpuBytes;

        private void accumulate(final TextureEntry entry) {
            count++;
            estimatedGpuBytes += entry.estimatedGpuBytes();
            if ("resident".equals(entry.state())) {
                residentCount++;
                residentGpuBytes += entry.estimatedGpuBytes();
                if (entry.evictable()) {
                    evictableResidentGpuBytes += entry.estimatedGpuBytes();
                }
            } else {
                nonResidentCount++;
            }
        }
    }

    private static final class RetentionSummary {
        private int  count;
        private int  residentCount;
        private long estimatedGpuBytes;
        private long residentGpuBytes;

        private void accumulate(final TextureEntry entry) {
            count++;
            estimatedGpuBytes += entry.estimatedGpuBytes();
            if ("resident".equals(entry.state())) {
                residentCount++;
                residentGpuBytes += entry.estimatedGpuBytes();
            }
        }
    }
}