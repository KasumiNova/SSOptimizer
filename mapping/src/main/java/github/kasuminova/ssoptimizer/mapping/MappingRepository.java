package github.kasuminova.ssoptimizer.mapping;

import java.util.List;
import java.util.Optional;

/**
 * 映射仓库抽象。
 * <p>
 * 由 Tiny v2 文件提供底层事实，外层通过该接口执行类、字段和方法的双向查询。
 */
public interface MappingRepository {

    /**
     * 返回仓库中的全部映射条目。
     *
     * @return 不可变映射列表
     */
    List<MappingEntry> entries();

    /**
     * 通过混淆类名查找类映射。
     *
     * @param obfuscatedName 混淆类名
     * @return 匹配的映射项
     */
    Optional<MappingEntry> findClassByObfuscatedName(String obfuscatedName);

    /**
     * 通过可读类名查找类映射。
     *
     * @param namedName 可读类名
     * @return 匹配的映射项
     */
    Optional<MappingEntry> findClassByNamedName(String namedName);

    /**
     * 通过混淆侧拥有者和字段名查找字段映射。
     *
     * @param ownerObfuscatedName 混淆类名
     * @param fieldName           混淆字段名
     * @return 匹配的映射项
     */
    Optional<MappingEntry> findFieldByObfuscatedName(String ownerObfuscatedName, String fieldName);

    /**
     * 通过可读侧拥有者和字段名查找字段映射。
     *
     * @param ownerNamedName 可读类名
     * @param fieldName      可读字段名
     * @return 匹配的映射项
     */
    Optional<MappingEntry> findFieldByNamedName(String ownerNamedName, String fieldName);

    /**
     * 通过混淆侧拥有者、方法名和描述符查找方法映射。
     *
     * @param ownerObfuscatedName 混淆类名
     * @param methodName          混淆方法名
     * @param descriptor          方法描述符
     * @return 匹配的映射项
     */
    Optional<MappingEntry> findMethodByObfuscatedName(String ownerObfuscatedName, String methodName, String descriptor);

    /**
     * 通过可读侧拥有者、方法名和描述符查找方法映射。
     *
     * @param ownerNamedName 可读类名
     * @param methodName     可读方法名
     * @param descriptor     方法描述符
     * @return 匹配的映射项
     */
    Optional<MappingEntry> findMethodByNamedName(String ownerNamedName, String methodName, String descriptor);
}