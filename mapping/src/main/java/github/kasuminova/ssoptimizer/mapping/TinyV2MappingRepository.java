package github.kasuminova.ssoptimizer.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Tiny v2 映射仓库实现。
 * <p>
 * 该实现把 Tiny v2 作为唯一读写格式，从 classpath 资源加载并建立双向查询索引。
 */
public final class TinyV2MappingRepository implements MappingRepository {
    private static final char INTERNAL_NAME_START = 'L';
    private static final char INTERNAL_NAME_END = ';';

    private final List<MappingEntry> entries;
    private final Map<String, MappingEntry> classByObfuscatedName;
    private final Map<String, MappingEntry> classByNamedName;
    private final Map<String, MappingEntry> fieldByObfuscatedKey;
    private final Map<String, MappingEntry> fieldByNamedKey;
    private final Map<String, MappingEntry> methodByObfuscatedKey;
    private final Map<String, MappingEntry> methodByNamedKey;

    private TinyV2MappingRepository(List<MappingEntry> entries) {
        this.entries = List.copyOf(entries);
        this.classByObfuscatedName = new LinkedHashMap<>();
        this.classByNamedName = new LinkedHashMap<>();
        this.fieldByObfuscatedKey = new LinkedHashMap<>();
        this.fieldByNamedKey = new LinkedHashMap<>();
        this.methodByObfuscatedKey = new LinkedHashMap<>();
        this.methodByNamedKey = new LinkedHashMap<>();

        for (MappingEntry entry : this.entries) {
            if (!entry.isClass()) {
                continue;
            }
            validateNamespaceBoundary(entry);
            classByObfuscatedName.put(entry.obfuscatedName(), entry);
            classByNamedName.put(entry.namedName(), entry);
        }

        for (MappingEntry entry : this.entries) {
            switch (entry.kind()) {
                case CLASS -> {
                    // 已在首轮建立索引。
                }
                case FIELD -> {
                    fieldByObfuscatedKey.put(fieldKey(entry.ownerObfuscatedName(), entry.obfuscatedName()), entry);
                    fieldByNamedKey.put(fieldKey(entry.ownerNamedName(), entry.namedName()), entry);
                }
                case METHOD -> {
                    methodByObfuscatedKey.put(methodKey(
                            entry.ownerObfuscatedName(),
                            entry.obfuscatedName(),
                            toObfuscatedDescriptor(entry.descriptor())), entry);
                    methodByNamedKey.put(methodKey(entry.ownerNamedName(), entry.namedName(), toNamedDescriptor(entry.descriptor())), entry);
                }
            }
        }
    }

    /**
     * 从默认 classpath 资源加载 Tiny v2 映射。
     *
     * @return 映射仓库
     */
    public static TinyV2MappingRepository loadDefault() {
        return loadForPlatform(MappingPlatform.current());
    }

    /**
     * 根据平台加载 Tiny v2 映射资源。
     *
     * @param platform 目标平台
     * @return 映射仓库
     */
    public static TinyV2MappingRepository loadForPlatform(final MappingPlatform platform) {
        final MappingPlatform resolvedPlatform = Objects.requireNonNull(platform, "platform");
        final String resourcePath = resourcePathFor(resolvedPlatform);
        InputStream resourceStream = TinyV2MappingRepository.class.getResourceAsStream(resourcePath);
        return loadFromResource(resourceStream, resourcePath);
    }

    /**
     * 返回当前默认平台对应的资源路径。
     *
     * @return 默认映射资源路径
     */
    public static String defaultResourcePath() {
        return resourcePathFor(MappingPlatform.current());
    }

    /**
     * 返回指定平台的资源路径。
     *
     * @param platform 目标平台
     * @return 平台映射资源路径
     */
    public static String resourcePathFor(final MappingPlatform platform) {
        return Objects.requireNonNull(platform, "platform").resourcePath();
    }

    /**
     * 从当前平台的 gzip 全量映射资源加载。
     * <p>
     * 无参入口刻意不暴露 {@link MappingPlatform}：运行期 agent 类分处 bootstrap 与 app
     * 两个类加载器，跨加载器传递 {@code MappingPlatform} 实例会记录 loader constraint，
     * 导致 bootstrap 侧内部调用以 {@link LinkageError} 崩溃。
     *
     * @return 全量映射仓库
     */
    public static TinyV2MappingRepository loadFullDefault() {
        return loadFullForPlatform(MappingPlatform.current());
    }

    /**
     * 根据平台加载 gzip 压缩的 Tiny v2 全量映射资源。
     * <p>
     * 全量表（含占位名与人工命名，约 22 万条目/平台）只供全量 deobf 模式使用，
     * 与 {@link #loadForPlatform(MappingPlatform)} 的 35 类桥接小表互斥。
     *
     * @param platform 目标平台
     * @return 全量映射仓库
     */
    public static TinyV2MappingRepository loadFullForPlatform(final MappingPlatform platform) {
        final MappingPlatform resolvedPlatform = Objects.requireNonNull(platform, "platform");
        final String resourcePath = fullResourcePathFor(resolvedPlatform);
        InputStream resourceStream = TinyV2MappingRepository.class.getResourceAsStream(resourcePath);
        return loadFromResource(resourceStream, resourcePath);
    }

    /**
     * 返回当前平台对应的 gzip 全量映射资源路径。
     *
     * @return 全量映射资源路径
     */
    public static String defaultFullResourcePath() {
        return fullResourcePathFor(MappingPlatform.current());
    }

    /**
     * 返回指定平台的 gzip 全量映射资源路径。
     *
     * @param platform 目标平台
     * @return 全量映射资源路径
     */
    public static String fullResourcePathFor(final MappingPlatform platform) {
        return "/mappings/ssoptimizer-" + Objects.requireNonNull(platform, "platform").id() + "-full.tiny.gz";
    }

    /**
     * 从给定 classpath 资源加载 Tiny v2 映射。
     * <p>
     * 资源路径以 {@code .gz} 结尾时按 gzip 压缩流解析，其余按明文解析。
     *
     * @param inputStream 资源输入流
     * @param resourcePath 资源路径，用于错误提示与压缩格式判定
     * @return 映射仓库
     */
    public static TinyV2MappingRepository loadFromResource(InputStream inputStream, String resourcePath) {
        if (inputStream == null) {
            throw new MappingLookupException("未找到 Tiny v2 映射资源: " + resourcePath);
        }

        try {
            InputStream stream = inputStream;
            if (resourcePath != null && resourcePath.endsWith(".gz")) {
                stream = new GZIPInputStream(stream);
            }
            try (InputStream closingStream = stream;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(closingStream, StandardCharsets.UTF_8))) {
                return new TinyV2MappingRepository(parse(reader, resourcePath));
            }
        } catch (IOException exception) {
            throw new MappingLookupException("读取 Tiny v2 映射失败: " + resourcePath, exception);
        }
    }

    /**
     * 从给定文件加载 Tiny v2 映射。
     *
     * @param mappingFile 映射文件路径
     * @return 映射仓库
     */
    public static TinyV2MappingRepository loadFromFile(java.nio.file.Path mappingFile) {
        Objects.requireNonNull(mappingFile, "mappingFile");
        try (InputStream stream = java.nio.file.Files.newInputStream(mappingFile)) {
            return loadFromResource(stream, mappingFile.toString());
        } catch (IOException exception) {
            throw new MappingLookupException("读取 Tiny v2 映射失败: " + mappingFile, exception);
        }
    }

    private static List<MappingEntry> parse(BufferedReader reader, String resourcePath) throws IOException {
        String header = reader.readLine();
        if (header == null) {
            throw new MappingLookupException("Tiny v2 映射为空: " + resourcePath);
        }

        String[] headerTokens = header.trim().split("\\s+");
        if (headerTokens.length < 4 || !"tiny".equals(headerTokens[0]) || !"2".equals(headerTokens[1])) {
            throw new MappingLookupException("Tiny v2 头部格式不正确: " + resourcePath);
        }

        List<MappingEntry> entries = new ArrayList<>();
        int currentClassIndex = -1;
        int currentMemberIndex = -1;

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }

            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == '\t') {
                indent++;
            }
            String content = line.substring(indent).stripTrailing();

            if (indent == 0) {
                String[] tokens = content.split("\\s+");
                if (tokens.length != 3 || !"c".equals(tokens[0])) {
                    throw new MappingLookupException("Tiny v2 类映射格式不正确: " + line);
                }
                entries.add(MappingEntry.classEntry(tokens[1], tokens[2]));
                currentClassIndex = entries.size() - 1;
                currentMemberIndex = -1;
                continue;
            }

            if (currentClassIndex < 0) {
                throw new MappingLookupException("Tiny v2 成员映射缺少类上下文: " + line);
            }

            if (content.equals("c") || content.startsWith("c ") || content.startsWith("c\t")) {
                // 注释行：缩进一级挂到所属类条目，缩进两级挂到所属成员条目。
                String comment = content.length() > 1 ? content.substring(1).strip() : "";
                int targetIndex = indent == 1 ? currentClassIndex : currentMemberIndex;
                if (targetIndex < 0) {
                    throw new MappingLookupException("Tiny v2 成员注释缺少成员上下文: " + line);
                }
                MappingEntry target = entries.get(targetIndex);
                String merged = target.comment() == null ? comment : target.comment() + '\n' + comment;
                entries.set(targetIndex, target.withComment(merged));
                continue;
            }

            if (indent != 1) {
                throw new MappingLookupException("Tiny v2 不支持的行缩进: " + line);
            }

            String[] tokens = content.split("\\s+");
            if (tokens.length != 4) {
                throw new MappingLookupException("Tiny v2 成员映射格式不正确: " + line);
            }

            MappingEntry currentClass = entries.get(currentClassIndex);
            if ("f".equals(tokens[0])) {
                entries.add(MappingEntry.fieldEntry(
                        currentClass.obfuscatedName(),
                        currentClass.namedName(),
                        tokens[1],
                        tokens[2],
                        tokens[3]));
                currentMemberIndex = entries.size() - 1;
                continue;
            }

            if ("m".equals(tokens[0])) {
                entries.add(MappingEntry.methodEntry(
                        currentClass.obfuscatedName(),
                        currentClass.namedName(),
                        tokens[1],
                        tokens[2],
                        tokens[3]));
                currentMemberIndex = entries.size() - 1;
                continue;
            }

            throw new MappingLookupException("Tiny v2 不支持的映射类型: " + tokens[0]);
        }

        return entries;
    }

    private static String fieldKey(String ownerName, String fieldName) {
        return ownerName + '#' + fieldName;
    }

    private static String methodKey(String ownerName, String methodName, String descriptor) {
        return ownerName + '#' + methodName + descriptor;
    }

    private String toNamedDescriptor(String descriptor) {
        return remapDescriptor(descriptor, classByObfuscatedName, true);
    }

    private String toObfuscatedDescriptor(String descriptor) {
        return remapDescriptor(descriptor, classByNamedName, false);
    }

    private static String remapDescriptor(String descriptor,
                                          Map<String, MappingEntry> classMappings,
                                          boolean toNamed) {
        if (descriptor == null || descriptor.indexOf(INTERNAL_NAME_START) < 0) {
            return descriptor;
        }

        StringBuilder builder = new StringBuilder(descriptor.length());
        int cursor = 0;
        while (cursor < descriptor.length()) {
            char current = descriptor.charAt(cursor);
            if (current != INTERNAL_NAME_START) {
                builder.append(current);
                cursor++;
                continue;
            }

            int end = descriptor.indexOf(INTERNAL_NAME_END, cursor);
            if (end < 0) {
                throw new MappingLookupException("描述符格式不正确: " + descriptor);
            }

            String internalName = descriptor.substring(cursor + 1, end);
            MappingEntry classEntry = classMappings.get(internalName);
            if (classEntry == null) {
                builder.append(INTERNAL_NAME_START).append(internalName).append(INTERNAL_NAME_END);
            } else if (toNamed) {
                builder.append(INTERNAL_NAME_START).append(classEntry.namedName()).append(INTERNAL_NAME_END);
            } else {
                builder.append(INTERNAL_NAME_START).append(classEntry.obfuscatedName()).append(INTERNAL_NAME_END);
            }
            cursor = end + 1;
        }

        return builder.toString();
    }

    private static void validateNamespaceBoundary(MappingEntry entry) {
        if (!entry.isClass()) {
            return;
        }
        if (isSsoptimizerNamespace(entry.obfuscatedName())) {
            return;
        }
        if (isSsoptimizerNamespace(entry.namedName())) {
            throw new MappingLookupException(
                    "外部类映射不得指向 SSOptimizer 命名空间: " + entry.obfuscatedName() + " -> " + entry.namedName());
        }
    }

    private static boolean isSsoptimizerNamespace(String internalName) {
        return internalName != null && internalName.startsWith("github/kasuminova/ssoptimizer/");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<MappingEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<MappingEntry> findClassByObfuscatedName(String obfuscatedName) {
        return Optional.ofNullable(classByObfuscatedName.get(obfuscatedName));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<MappingEntry> findClassByNamedName(String namedName) {
        return Optional.ofNullable(classByNamedName.get(namedName));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<MappingEntry> findFieldByObfuscatedName(String ownerObfuscatedName, String fieldName) {
        return Optional.ofNullable(fieldByObfuscatedKey.get(fieldKey(ownerObfuscatedName, fieldName)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<MappingEntry> findFieldByNamedName(String ownerNamedName, String fieldName) {
        return Optional.ofNullable(fieldByNamedKey.get(fieldKey(ownerNamedName, fieldName)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<MappingEntry> findMethodByObfuscatedName(String ownerObfuscatedName, String methodName, String descriptor) {
        return Optional.ofNullable(methodByObfuscatedKey.get(methodKey(ownerObfuscatedName, methodName, descriptor)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<MappingEntry> findMethodByNamedName(String ownerNamedName, String methodName, String descriptor) {
        return Optional.ofNullable(methodByNamedKey.get(methodKey(ownerNamedName, methodName, descriptor)));
    }

    /**
     * 通过混淆类名直接获取类映射。
     *
     * @param obfuscatedName 混淆类名
     * @return 类映射
     */
    public MappingEntry requireClassByObfuscatedName(String obfuscatedName) {
        return findClassByObfuscatedName(obfuscatedName)
                .orElseThrow(() -> new MappingLookupException("未找到类映射: " + obfuscatedName));
    }

    /**
     * 通过可读类名直接获取类映射。
     *
     * @param namedName 可读类名
     * @return 类映射
     */
    public MappingEntry requireClassByNamedName(String namedName) {
        return findClassByNamedName(namedName)
                .orElseThrow(() -> new MappingLookupException("未找到类映射: " + namedName));
    }

    /**
     * 通过可读类名和字段名直接获取字段映射。
     *
     * @param ownerNamedName 可读类名
     * @param fieldName      可读字段名
     * @return 字段映射
     */
    public MappingEntry requireFieldByNamedName(String ownerNamedName, String fieldName) {
        return findFieldByNamedName(ownerNamedName, fieldName)
                .orElseThrow(() -> new MappingLookupException("未找到字段映射: " + ownerNamedName + '#' + fieldName));
    }

    /**
     * 通过混淆类名和字段名直接获取字段映射。
     *
     * @param ownerObfuscatedName 混淆类名
     * @param fieldName          混淆字段名
     * @return 字段映射
     */
    public MappingEntry requireFieldByObfuscatedName(String ownerObfuscatedName, String fieldName) {
        return findFieldByObfuscatedName(ownerObfuscatedName, fieldName)
                .orElseThrow(() -> new MappingLookupException("未找到字段映射: " + ownerObfuscatedName + '#' + fieldName));
    }

    /**
     * 通过混淆类名、方法名和描述符直接获取方法映射。
     *
     * @param ownerObfuscatedName 混淆类名
     * @param methodName          混淆方法名
     * @param descriptor          方法描述符
     * @return 方法映射
     */
    public MappingEntry requireMethodByObfuscatedName(String ownerObfuscatedName, String methodName, String descriptor) {
        return findMethodByObfuscatedName(ownerObfuscatedName, methodName, descriptor)
                .orElseThrow(() -> new MappingLookupException("未找到方法映射: " + ownerObfuscatedName + '#' + methodName + descriptor));
    }

    /**
     * 通过可读类名、方法名和描述符直接获取方法映射。
     *
     * @param ownerNamedName 可读类名
     * @param methodName     可读方法名
     * @param descriptor     方法描述符
     * @return 方法映射
     */
    public MappingEntry requireMethodByNamedName(String ownerNamedName, String methodName, String descriptor) {
        return findMethodByNamedName(ownerNamedName, methodName, descriptor)
                .orElseThrow(() -> new MappingLookupException("未找到方法映射: " + ownerNamedName + '#' + methodName + descriptor));
    }

    /**
     * 直接从给定的条目列表构造仓库，主要用于测试和导出。
     *
     * @param entries 映射条目列表
     * @return 仓库实例
     */
    public static TinyV2MappingRepository of(List<MappingEntry> entries) {
        return new TinyV2MappingRepository(List.copyOf(Objects.requireNonNull(entries, "entries")));
    }
}