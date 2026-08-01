package github.kasuminova.ssoptimizer.mapping;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.util.Objects;

/**
 * 基于 Tiny v2 映射仓库的字节码重映射器。
 * <p>
 * 该组件负责在 class 级别统一改写类名、字段名、方法名和描述符，供运行时
 * {@code RuntimeRemapTransformer} 与编译期 jar remap 共用同一套规则。
 */
public final class BytecodeRemapper {
    private final MappingRepository repository;
    private final MappingDirection direction;

    /**
     * 创建字节码重映射器。
     *
     * @param repository 映射仓库
     * @param direction  重映射方向
     */
    public BytecodeRemapper(MappingRepository repository, MappingDirection direction) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.direction = Objects.requireNonNull(direction, "direction");
    }

    /**
     * 重映射单个类文件字节码。
     *
     * @param classfileBuffer 原始类文件字节码
     * @return 重映射结果
     */
    public RemappedClass remapClass(byte[] classfileBuffer) {
        Objects.requireNonNull(classfileBuffer, "classfileBuffer");

        ClassReader reader = new ClassReader(classfileBuffer);
        RepositoryBackedRemapper remapper = new RepositoryBackedRemapper();
        // 不复用原常量池（new ClassWriter(reader, 0) 会逐字拷贝原 CP，把混淆器留下的
        // 孤儿 Methodref/Fieldref 项（如未被任何指令引用的 "do.new"）带进产物；
        // 运行期 SanitizingRemapper 只能改写可达项，JDK 25 对变换器修改过的类强制
        // 格式检查时会被孤儿项击杀）。全新 ClassWriter 只保留可达常量。
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassRemapper(writer, remapper), 0);

        String inputInternalName = reader.getClassName();
        String outputInternalName = remapper.map(inputInternalName);
        byte[] outputBytes = remapper.modified() ? writer.toByteArray() : classfileBuffer;
        return new RemappedClass(inputInternalName, outputInternalName, outputBytes, remapper.modified());
    }

    /**
     * 单个类 remap 的结果。
     *
     * @param inputInternalName  输入类名
     * @param outputInternalName 输出类名
     * @param bytecode           输出字节码
     * @param modified           是否发生改写
     */
    public record RemappedClass(String inputInternalName,
                                String outputInternalName,
                                byte[] bytecode,
                                boolean modified) {
    }

    private final class RepositoryBackedRemapper extends Remapper {
        private boolean modified;

        @Override
        public String mapDesc(String descriptor) {
            String mappedDescriptor = super.mapDesc(descriptor);
            if (!descriptor.equals(mappedDescriptor)) {
                modified = true;
            }
            return mappedDescriptor;
        }

        @Override
        public String mapMethodDesc(String descriptor) {
            String mappedDescriptor = super.mapMethodDesc(descriptor);
            if (!descriptor.equals(mappedDescriptor)) {
                modified = true;
            }
            return mappedDescriptor;
        }

        @Override
        public String map(String internalName) {
            MappingEntry classEntry = switch (direction) {
                case OBFUSCATED_TO_NAMED -> repository.findClassByObfuscatedName(internalName).orElse(null);
                case NAMED_TO_OBFUSCATED -> repository.findClassByNamedName(internalName).orElse(null);
            };
            if (classEntry == null) {
                return internalName;
            }

            String mappedName = switch (direction) {
                case OBFUSCATED_TO_NAMED -> classEntry.namedName();
                case NAMED_TO_OBFUSCATED -> classEntry.obfuscatedName();
            };
            if (!internalName.equals(mappedName)) {
                modified = true;
            }
            return mappedName;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            MappingEntry fieldEntry = switch (direction) {
                case OBFUSCATED_TO_NAMED -> repository.findFieldByObfuscatedName(owner, name).orElse(null);
                case NAMED_TO_OBFUSCATED -> repository.findFieldByNamedName(owner, name).orElse(null);
            };
            if (fieldEntry == null) {
                return name;
            }

            String mappedName = switch (direction) {
                case OBFUSCATED_TO_NAMED -> fieldEntry.namedName();
                case NAMED_TO_OBFUSCATED -> fieldEntry.obfuscatedName();
            };
            if (!name.equals(mappedName)) {
                modified = true;
            }
            return mappedName;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            MappingEntry methodEntry = switch (direction) {
                case OBFUSCATED_TO_NAMED -> repository.findMethodByObfuscatedName(owner, name, descriptor).orElse(null);
                case NAMED_TO_OBFUSCATED -> repository.findMethodByNamedName(owner, name, descriptor).orElse(null);
            };
            if (methodEntry == null) {
                return name;
            }

            String mappedName = switch (direction) {
                case OBFUSCATED_TO_NAMED -> methodEntry.namedName();
                case NAMED_TO_OBFUSCATED -> methodEntry.obfuscatedName();
            };
            if (!name.equals(mappedName)) {
                modified = true;
            }
            return mappedName;
        }

        boolean modified() {
            return modified;
        }
    }
}
