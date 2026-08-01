package github.kasuminova.ssoptimizer.mapping.gen;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 单个 class 文件的结构信息。
 * <p>
 * 该记录是结构指纹与占位名生成的输入：只采集继承关系、字段与方法签名，
 * 不读取方法体，保证从游戏 jar 扫描的成本与类数量线性相关。
 *
 * @param name       类内部名（{@code com/fs/graphics/L} 形式）
 * @param superName  父类内部名；接口或无父类时为 {@code null}/{@code java/lang/Object}
 * @param interfaces 直接实现的接口内部名，按声明顺序
 * @param fields     字段列表，按 class 文件声明顺序
 * @param methods    方法列表（含 {@code <init>}/{@code <clinit>}），按声明顺序
 */
public record ClassStructure(String name,
                             String superName,
                             List<String> interfaces,
                             List<Member> fields,
                             List<Member> methods) {

    /**
     * 类成员（字段或方法）的签名信息。
     *
     * @param name          成员名（混淆侧原始名）
     * @param desc          描述符（混淆侧原始形式）
     * @param access        访问标志位
     * @param constantValue 字段 ConstantValue 属性的常量值（仅 {@code static final} 常量字段有值，
     *                      方法与非常量字段为 {@code null}）；不参与结构指纹计算，
     *                      供机械预命名（如字符串常量字段按值派生名）使用
     */
    public record Member(String name, String desc, int access, Object constantValue) {
        /**
         * 非常量成员的构造（constantValue 为 {@code null}）。
         *
         * @param name   成员名（混淆侧原始名）
         * @param desc   描述符（混淆侧原始形式）
         * @param access 访问标志位
         */
        public Member(String name, String desc, int access) {
            this(name, desc, access, null);
        }
    }

    /**
     * 扫描给定 jar 列表中的全部 class，返回按内部名排序的结构列表。
     *
     * @param jarFiles 输入 jar（只读取 {@code .class} 条目）
     * @return 排序后的类结构列表
     * @throws IOException 若读取失败
     */
    public static List<ClassStructure> scan(List<java.nio.file.Path> jarFiles) throws IOException {
        Objects.requireNonNull(jarFiles, "jarFiles");
        List<ClassStructure> classes = new ArrayList<>();
        for (java.nio.file.Path jarFile : jarFiles) {
            try (JarFile jar = new JarFile(jarFile.toFile())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                        continue;
                    }
                    try (InputStream stream = jar.getInputStream(entry)) {
                        classes.add(read(stream));
                    }
                }
            }
        }
        classes.sort(Comparator.comparing(ClassStructure::name));
        return classes;
    }

    private static ClassStructure read(InputStream stream) throws IOException {
        ClassReader reader = new ClassReader(stream);
        StructureCollector collector = new StructureCollector();
        reader.accept(collector, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return collector.toClassStructure();
    }

    private static final class StructureCollector extends ClassVisitor {
        private String name;
        private String superName;
        private List<String> interfaces = List.of();
        private final List<Member> fields = new ArrayList<>();
        private final List<Member> methods = new ArrayList<>();

        private StructureCollector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.name = name;
            this.superName = superName;
            this.interfaces = interfaces == null ? List.of() : List.of(interfaces);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            fields.add(new Member(name, descriptor, access, value));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            methods.add(new Member(name, descriptor, access));
            return null;
        }

        private ClassStructure toClassStructure() {
            return new ClassStructure(name, superName, interfaces, List.copyOf(fields), List.copyOf(methods));
        }
    }
}
