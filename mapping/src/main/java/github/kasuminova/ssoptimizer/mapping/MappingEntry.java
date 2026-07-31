package github.kasuminova.ssoptimizer.mapping;

import java.util.Objects;

/**
 * 单条 Tiny v2 映射记录。
 * <p>
 * 该类型统一描述类、字段和方法映射，便于查询层和导出层共享同一份事实数据。
 */
public final class MappingEntry {
    /**
     * 映射条目类型。
     */
    public enum Kind {
        /** 类映射。 */
        CLASS,
        /** 字段映射。 */
        FIELD,
        /** 方法映射。 */
        METHOD
    }

    private final Kind kind;
    private final String ownerObfuscatedName;
    private final String ownerNamedName;
    private final String obfuscatedName;
    private final String namedName;
    private final String descriptor;
    private final String comment;

    private MappingEntry(Kind kind,
                         String ownerObfuscatedName,
                         String ownerNamedName,
                         String obfuscatedName,
                         String namedName,
                         String descriptor,
                         String comment) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.ownerObfuscatedName = ownerObfuscatedName;
        this.ownerNamedName = ownerNamedName;
        this.obfuscatedName = Objects.requireNonNull(obfuscatedName, "obfuscatedName");
        this.namedName = Objects.requireNonNull(namedName, "namedName");
        this.descriptor = descriptor;
        this.comment = comment;
    }

    /**
     * 创建类映射条目。
     *
     * @param obfuscatedName 混淆类名
     * @param namedName      可读类名
     * @return 类映射条目
     */
    public static MappingEntry classEntry(String obfuscatedName, String namedName) {
        return new MappingEntry(Kind.CLASS, null, null, obfuscatedName, namedName, null, null);
    }

    /**
     * 创建字段映射条目。
     *
     * @param ownerObfuscatedName 混淆类名
     * @param ownerNamedName      可读类名
     * @param obfuscatedName      混淆字段名
     * @param namedName           可读字段名
     * @param descriptor          字段描述符
     * @return 字段映射条目
     */
    public static MappingEntry fieldEntry(String ownerObfuscatedName,
                                          String ownerNamedName,
                                          String obfuscatedName,
                                          String namedName,
                                          String descriptor) {
        return new MappingEntry(Kind.FIELD, ownerObfuscatedName, ownerNamedName, obfuscatedName, namedName, descriptor, null);
    }

    /**
     * 创建方法映射条目。
     *
     * @param ownerObfuscatedName 混淆类名
     * @param ownerNamedName      可读类名
     * @param obfuscatedName      混淆方法名
     * @param namedName           可读方法名
     * @param descriptor          方法描述符
     * @return 方法映射条目
     */
    public static MappingEntry methodEntry(String ownerObfuscatedName,
                                           String ownerNamedName,
                                           String obfuscatedName,
                                           String namedName,
                                           String descriptor) {
        return new MappingEntry(Kind.METHOD, ownerObfuscatedName, ownerNamedName, obfuscatedName, namedName, descriptor, null);
    }

    /**
     * 返回映射条目类型。
     *
     * @return 类型
     */
    public Kind kind() {
        return kind;
    }

    /**
     * 返回混淆侧拥有者类名。
     *
     * @return 混淆侧类名；类条目返回 {@code null}
     */
    public String ownerObfuscatedName() {
        return ownerObfuscatedName;
    }

    /**
     * 返回可读侧拥有者类名。
     *
     * @return 可读侧类名；类条目返回 {@code null}
     */
    public String ownerNamedName() {
        return ownerNamedName;
    }

    /**
     * 返回混淆名称。
     *
     * @return 混淆名称
     */
    public String obfuscatedName() {
        return obfuscatedName;
    }

    /**
     * 返回可读名称。
     *
     * @return 可读名称
     */
    public String namedName() {
        return namedName;
    }

    /**
     * 返回描述符。
     *
     * @return 描述符；类条目返回 {@code null}
     */
    public String descriptor() {
        return descriptor;
    }

    /**
     * 返回映射注释。
     * <p>
     * 注释来自 Tiny v2 注释行（类行下的 {@code \tc ...}、成员行下的 {@code \t\tc ...}），
     * 是映射维护者记录命名来源与证据的载体，不进入字节码。
     *
     * @return 注释文本；无注释返回 {@code null}
     */
    public String comment() {
        return comment;
    }

    /**
     * 返回附带指定注释的条目副本。
     *
     * @param newComment 注释文本；{@code null} 表示无注释
     * @return 新条目
     */
    public MappingEntry withComment(String newComment) {
        return new MappingEntry(kind, ownerObfuscatedName, ownerNamedName, obfuscatedName, namedName, descriptor, newComment);
    }

    /**
     * 判断条目是否为类映射。
     *
     * @return 是类映射返回 {@code true}
     */
    public boolean isClass() {
        return kind == Kind.CLASS;
    }

    /**
     * 判断条目是否为字段映射。
     *
     * @return 是字段映射返回 {@code true}
     */
    public boolean isField() {
        return kind == Kind.FIELD;
    }

    /**
     * 判断条目是否为方法映射。
     *
     * @return 是方法映射返回 {@code true}
     */
    public boolean isMethod() {
        return kind == Kind.METHOD;
    }
}