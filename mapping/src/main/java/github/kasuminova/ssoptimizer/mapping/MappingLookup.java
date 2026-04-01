package github.kasuminova.ssoptimizer.mapping;

import java.util.Objects;

/**
 * 映射查询外壳。
 * <p>
 * 对外提供带可读异常的必查 API，避免上层代码自己处理空值和重复的错误信息拼装。
 */
public final class MappingLookup {
    private final MappingRepository repository;

    /**
     * 创建映射查询器。
     *
     * @param repository 映射仓库
     */
    public MappingLookup(MappingRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * 通过混淆类名获取类映射。
     *
     * @param obfuscatedName 混淆类名
     * @return 类映射
     */
    public MappingEntry requireClassByObfuscatedName(String obfuscatedName) {
        return repository.findClassByObfuscatedName(obfuscatedName)
                .orElseThrow(() -> new MappingLookupException("未找到类映射: " + obfuscatedName));
    }

    /**
     * 通过可读类名获取类映射。
     *
     * @param namedName 可读类名
     * @return 类映射
     */
    public MappingEntry requireClassByNamedName(String namedName) {
        return repository.findClassByNamedName(namedName)
                .orElseThrow(() -> new MappingLookupException("未找到类映射: " + namedName));
    }

    /**
     * 通过可读类名和字段名获取字段映射。
     *
     * @param ownerNamedName 可读类名
     * @param fieldName      可读字段名
     * @return 字段映射
     */
    public MappingEntry requireFieldByNamedName(String ownerNamedName, String fieldName) {
        return repository.findFieldByNamedName(ownerNamedName, fieldName)
                .orElseThrow(() -> new MappingLookupException("未找到字段映射: " + ownerNamedName + '#' + fieldName));
    }

    /**
     * 通过混淆类名和字段名获取字段映射。
     *
     * @param ownerObfuscatedName 混淆类名
     * @param fieldName          混淆字段名
     * @return 字段映射
     */
    public MappingEntry requireFieldByObfuscatedName(String ownerObfuscatedName, String fieldName) {
        return repository.findFieldByObfuscatedName(ownerObfuscatedName, fieldName)
                .orElseThrow(() -> new MappingLookupException("未找到字段映射: " + ownerObfuscatedName + '#' + fieldName));
    }

    /**
     * 通过混淆类名、方法名和描述符获取方法映射。
     *
     * @param ownerObfuscatedName 混淆类名
     * @param methodName          混淆方法名
     * @param descriptor          方法描述符
     * @return 方法映射
     */
    public MappingEntry requireMethodByObfuscatedName(String ownerObfuscatedName, String methodName, String descriptor) {
        return repository.findMethodByObfuscatedName(ownerObfuscatedName, methodName, descriptor)
                .orElseThrow(() -> new MappingLookupException("未找到方法映射: " + ownerObfuscatedName + '#' + methodName + descriptor));
    }

    /**
     * 通过可读类名、方法名和描述符获取方法映射。
     *
     * @param ownerNamedName 可读类名
     * @param methodName     可读方法名
     * @param descriptor     方法描述符
     * @return 方法映射
     */
    public MappingEntry requireMethodByNamedName(String ownerNamedName, String methodName, String descriptor) {
        return repository.findMethodByNamedName(ownerNamedName, methodName, descriptor)
                .orElseThrow(() -> new MappingLookupException("未找到方法映射: " + ownerNamedName + '#' + methodName + descriptor));
    }
}