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

/**
 * Tiny v2 映射仓库实现。
 * <p>
 * 该实现把 Tiny v2 作为唯一读写格式，从 classpath 资源加载并建立双向查询索引。
 */
public final class TinyV2MappingRepository implements MappingRepository {
    private static final String DEFAULT_RESOURCE = "/mappings/ssoptimizer.tiny";
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
                    methodByObfuscatedKey.put(methodKey(entry.ownerObfuscatedName(), entry.obfuscatedName(), entry.descriptor()), entry);
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
        return loadFromResource(TinyV2MappingRepository.class.getResourceAsStream(DEFAULT_RESOURCE), DEFAULT_RESOURCE);
    }

    /**
     * 从给定 classpath 资源加载 Tiny v2 映射。
     *
     * @param inputStream 资源输入流
     * @param resourcePath 资源路径，用于错误提示
     * @return 映射仓库
     */
    public static TinyV2MappingRepository loadFromResource(InputStream inputStream, String resourcePath) {
        if (inputStream == null) {
            throw new MappingLookupException("未找到 Tiny v2 映射资源: " + resourcePath);
        }

        try (InputStream stream = inputStream;
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return new TinyV2MappingRepository(parse(reader, resourcePath));
        } catch (IOException exception) {
            throw new MappingLookupException("读取 Tiny v2 映射失败: " + resourcePath, exception);
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
        MappingEntry currentClass = null;

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }

            String trimmed = line.stripLeading();
            if (trimmed.startsWith("c ")) {
                String[] tokens = trimmed.split("\\s+");
                if (tokens.length != 3) {
                    throw new MappingLookupException("Tiny v2 类映射格式不正确: " + line);
                }
                currentClass = MappingEntry.classEntry(tokens[1], tokens[2]);
                entries.add(currentClass);
                continue;
            }

            if (currentClass == null) {
                throw new MappingLookupException("Tiny v2 成员映射缺少类上下文: " + line);
            }

            String[] tokens = trimmed.split("\\s+");
            if (tokens.length != 4) {
                throw new MappingLookupException("Tiny v2 成员映射格式不正确: " + line);
            }

            if ("f".equals(tokens[0])) {
                entries.add(MappingEntry.fieldEntry(
                        currentClass.obfuscatedName(),
                        currentClass.namedName(),
                        tokens[1],
                        tokens[2],
                        tokens[3]));
                continue;
            }

            if ("m".equals(tokens[0])) {
                entries.add(MappingEntry.methodEntry(
                        currentClass.obfuscatedName(),
                        currentClass.namedName(),
                        tokens[1],
                        tokens[2],
                        tokens[3]));
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