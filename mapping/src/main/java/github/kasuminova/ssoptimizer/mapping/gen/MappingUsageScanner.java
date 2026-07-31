package github.kasuminova.ssoptimizer.mapping.gen;

import github.kasuminova.ssoptimizer.mapping.MappingEntry;
import github.kasuminova.ssoptimizer.mapping.TinyV2MappingRepository;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 映射使用度扫描器。
 * <p>
 * 扫描 app / agent-api / mod-optimizations 等消费侧字节码（jar 或 class 目录），
 * 收集对游戏类/成员的静态引用（字段访问、方法调用、类型常量与
 * {@code com/fs/...} 类名字符串常量），与构建期全量表做 named 侧连接，
 * 把每条引用归类为：
 * <ul>
 *     <li>{@code semantic}——人工表 / scope 片段已语义命名；</li>
 *     <li>{@code identity}——保持原名类及其成员（原名即 named）；</li>
 *     <li>{@code promoted}——生成层自动提升的原始名（跨平台一致性不受保证，
 *     消费侧引用它属于纪律违规，应迁入人工表）；</li>
 *     <li>{@code placeholder}——哈希占位名（严重违规，理论上不应出现）。</li>
 * </ul>
 * 报告同时给出按引用次数排序的热点类清单（含每类剩余占位成员数），
 * 作为语义命名 swarm 的优先级输入。
 * <p>
 * 已知盲区：Mixin targets / 反射等方法名字符串引用无法归属 owner，不统计；
 * 类名字符串常量只计入类级引用次数。
 */
public final class MappingUsageScanner {
    /** 占位成员名（{@code f_<hash8>} / {@code m_<hash8>}，可带冲突序号）。 */
    private static final Pattern PLACEHOLDER_MEMBER_NAME = Pattern.compile("[fm]_[0-9a-f]{8}(_\\d+)?");
    /** 占位类名（{@code C_<hash8>}，可带冲突序号）。 */
    private static final Pattern PLACEHOLDER_CLASS_SIMPLE_NAME = Pattern.compile("C_[0-9a-f]{8}(_\\d+)?");

    /** 语义层成员 key 分隔符。 */
    private static final char KEY_SEPARATOR = '#';

    /**
     * 单次扫描结果：分类后的成员引用、类引用计数与报告行。
     *
     * @param referencedClasses  被引用的游戏类（named）→ 引用次数
     * @param violations         占位名引用（应为空）
     * @param promotedReferences 提升名引用（应迁入人工表）
     * @param reportLines        完整报告文本行
     */
    public record UsageScanResult(Map<String, Integer> referencedClasses,
                                  List<String> violations,
                                  List<String> promotedReferences,
                                  List<String> reportLines) {
    }

    /**
     * 扫描消费侧字节码并生成使用度报告。
     *
     * @param inputs           消费侧 jar 文件或 class 目录
     * @param fullRepository   构建期全量表
     * @param semanticEntries  语义层条目（人工表 + scope 片段，用于区分语义名与生成名）
     * @param identityClasses  保持原名类（named 名集合）
     * @return 扫描结果与报告行
     */
    public UsageScanResult scan(List<Path> inputs,
                                TinyV2MappingRepository fullRepository,
                                List<MappingEntry> semanticEntries,
                                Set<String> identityClasses) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(fullRepository, "fullRepository");
        Objects.requireNonNull(semanticEntries, "semanticEntries");
        Objects.requireNonNull(identityClasses, "identityClasses");

        Set<String> semanticMemberKeys = new TreeSet<>();
        for (MappingEntry entry : semanticEntries) {
            if (!entry.isClass()) {
                semanticMemberKeys.add(memberKey(entry.ownerNamedName(), entry.kind() == MappingEntry.Kind.FIELD ? "f" : "m",
                        entry.namedName()));
            }
        }

        Set<String> tableClassNames = new TreeSet<>();
        Map<String, Integer> placeholderMembersByClass = new LinkedHashMap<>();
        for (MappingEntry entry : fullRepository.entries()) {
            if (entry.isClass()) {
                tableClassNames.add(entry.namedName());
            } else if (PLACEHOLDER_MEMBER_NAME.matcher(entry.namedName()).matches()) {
                placeholderMembersByClass.merge(entry.ownerNamedName(), 1, Integer::sum);
            }
        }

        Map<String, Integer> classReferenceCounts = new TreeMap<>();
        Map<String, Integer> memberReferenceCounts = new TreeMap<>();
        collectReferences(inputs, tableClassNames, classReferenceCounts, memberReferenceCounts);

        List<String> violations = new ArrayList<>();
        List<String> promotedReferences = new ArrayList<>();
        int semanticCount = 0;
        int identityCount = 0;
        for (Map.Entry<String, Integer> reference : memberReferenceCounts.entrySet()) {
            String key = reference.getKey();
            String owner = key.substring(0, key.indexOf(KEY_SEPARATOR));
            String name = key.substring(key.lastIndexOf(KEY_SEPARATOR) + 1);
            if (identityClasses.contains(owner)) {
                identityCount++;
            } else if (semanticMemberKeys.contains(key)) {
                semanticCount++;
            } else if (PLACEHOLDER_MEMBER_NAME.matcher(name).matches()) {
                violations.add(key + " x" + reference.getValue());
            } else {
                promotedReferences.add(key + " x" + reference.getValue());
            }
        }

        List<String> reportLines = renderReport(classReferenceCounts, memberReferenceCounts,
                placeholderMembersByClass, violations, promotedReferences,
                semanticCount, identityCount);
        return new UsageScanResult(classReferenceCounts, violations, promotedReferences, reportLines);
    }

    /**
     * 成员 key：{@code ownerNamed#kind#namedName}。不含描述符——分类粒度到名即可，
     * 同名重载中只要有一个是语义命名就视作语义层（极罕见的误判可接受）。
     */
    private static String memberKey(String ownerNamed, String kind, String namedName) {
        return ownerNamed + KEY_SEPARATOR + kind + KEY_SEPARATOR + namedName;
    }

    private void collectReferences(List<Path> inputs,
                                   Set<String> tableClassNames,
                                   Map<String, Integer> classReferenceCounts,
                                   Map<String, Integer> memberReferenceCounts) {
        for (Path input : inputs) {
            if (Files.isDirectory(input)) {
                try (Stream<Path> files = Files.walk(input)) {
                    for (Path classFile : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                        try (InputStream stream = Files.newInputStream(classFile)) {
                            scanClass(stream, tableClassNames, classReferenceCounts, memberReferenceCounts);
                        }
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("扫描 class 目录失败: " + input, exception);
                }
            } else if (input.toString().endsWith(".jar")) {
                try (ZipFile jar = new ZipFile(input.toFile())) {
                    List<? extends ZipEntry> classEntries = jar.stream()
                            .filter(entry -> entry.getName().endsWith(".class"))
                            .toList();
                    for (ZipEntry entry : classEntries) {
                        try (InputStream stream = jar.getInputStream(entry)) {
                            scanClass(stream, tableClassNames, classReferenceCounts, memberReferenceCounts);
                        }
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("扫描 jar 失败: " + input, exception);
                }
            } else {
                throw new IllegalArgumentException("不支持的扫描输入（只接受 jar 或 class 目录）: " + input);
            }
        }
    }

    private void scanClass(InputStream classBytes,
                           Set<String> tableClassNames,
                           Map<String, Integer> classReferenceCounts,
                           Map<String, Integer> memberReferenceCounts) throws IOException {
        ClassReader reader = new ClassReader(classBytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        recordMember(owner, "f", fieldName);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        recordClass(owner);
                        // 构造/静态初始化不产生映射条目，不参与分层分类。
                        if (!"<init>".equals(methodName) && !"<clinit>".equals(methodName)) {
                            recordMember(owner, "m", methodName);
                        }
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Type type) {
                            recordClass(type.getInternalName());
                        } else if (value instanceof String string) {
                            // Mixin targets / 反射等类名字符串常量（internal 或 dotted 形式）
                            String internalName = string.replace('.', '/');
                            if (internalName.startsWith("com/fs/")) {
                                recordClass(internalName);
                            }
                        }
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        recordClass(type);
                    }

                    private void recordClass(String internalName) {
                        if (tableClassNames.contains(internalName)) {
                            classReferenceCounts.merge(internalName, 1, Integer::sum);
                        }
                    }

                    private void recordMember(String owner, String kind, String memberName) {
                        recordClass(owner);
                        if (tableClassNames.contains(owner)) {
                            memberReferenceCounts.merge(memberKey(owner, kind, memberName), 1, Integer::sum);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static List<String> renderReport(Map<String, Integer> classReferenceCounts,
                                             Map<String, Integer> memberReferenceCounts,
                                             Map<String, Integer> placeholderMembersByClass,
                                             List<String> violations,
                                             List<String> promotedReferences,
                                             int semanticCount,
                                             int identityCount) {
        List<String> lines = new ArrayList<>();
        lines.add("# 映射使用度扫描报告");
        lines.add("# 消费侧（app/agent-api/mod-optimizations 字节码）对游戏类/成员的静态引用统计。");
        lines.add("被引用游戏类数: " + classReferenceCounts.size());
        lines.add("被引用成员数: " + memberReferenceCounts.size());
        lines.add("  语义命名引用: " + semanticCount);
        lines.add("  保持原名类成员引用: " + identityCount);
        lines.add("  提升名引用（应迁入人工表）: " + promotedReferences.size());
        lines.add("  占位名引用（违规，应为 0）: " + violations.size());

        lines.add("");
        lines.add("## 占位名引用（违规）");
        if (violations.isEmpty()) {
            lines.add("（无）");
        } else {
            lines.addAll(violations);
        }

        lines.add("");
        lines.add("## 提升名引用（跨平台一致性不受保证，建议迁入人工表）");
        if (promotedReferences.isEmpty()) {
            lines.add("（无）");
        } else {
            lines.addAll(promotedReferences);
        }

        lines.add("");
        lines.add("## 热点类（按引用次数排序，附剩余占位成员数——swarm 语义命名优先级输入）");
        Map<String, Integer> sorted = new TreeMap<>(classReferenceCounts);
        sorted.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> lines.add(entry.getKey()
                        + " refs=" + entry.getValue()
                        + " placeholderMembers=" + placeholderMembersByClass.getOrDefault(entry.getKey(), 0)));
        return lines;
    }
}
