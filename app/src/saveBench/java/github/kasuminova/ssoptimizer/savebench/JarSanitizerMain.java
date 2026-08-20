package github.kasuminova.ssoptimizer.savebench;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * named 游戏 jar 的非法字段/方法名清洗工具（离线基准专用构建步骤）。
 *
 * <p>背景：游戏混淆器（Allatori 系）会产生 {@code String.new}、{@code void.new} 这类
 * 含 '.' 的成员名——class 文件版本 ≥ 51 时被 JVM 拒绝（{@code ClassFormatError:
 * Illegal field name}）。游戏运行期由 NanoForge remap 把这类未命名成员回退为
 * intermediary 名（如 {@code f_67b9c92e_10}）所以能跑；但 SourceSector 产出的 named jar
 * 对未命名成员保留了混淆原名，导致 JDK 25 下离线环境无法加载这些类。</p>
 *
 * <p>实现方式：<b>常量池原位修补</b>而非 ASM 重写——ASM 对该混淆器产物的 StackMapTable
 * 往返重编码会损坏帧偏移（已实测 VerifyError），而只把「名字位置」引用的 Utf8 常量中的
 * 非法字符替换为等长 '_' 不涉及任何结构重编码，逐字节安全。</p>
 *
 * <p>安全性论证：</p>
 * <ul>
 *   <li>替换是字节级确定性的，定义与引用共享同一 Utf8 常量，天然一致；</li>
 *   <li>等长替换（ASCII 单字节 '_'）不改变常量池结构与其他条目偏移；</li>
 *   <li>实测真实存档无 intermediary/非法形态字段元素名（58MB 档全量扫描），
 *       即可序列化字段全是合法名，清洗不影响存档 XML 语义；</li>
 *   <li>非法名是混淆器生成的内部成员名，SSOptimizer 源码不曾按名引用。</li>
 * </ul>
 *
 * <p>已知根因（待 SourceSector 修复）：named jar 生成对缺失 named 映射的成员应回退
 * intermediary 名（与 NanoForge {@code TinyV2MappingRepository} 的规则一致），
 * 而不是保留混淆原名。修复后本工具会报告零改写，届时可移除。</p>
 */
public final class JarSanitizerMain {
    private JarSanitizerMain() {
    }

    /**
     * 入口。
     *
     * @param args args[0] 为输出目录，其余为待清洗 jar 路径；输出 jar 与输入同名
     */
    public static void main(final String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("用法: JarSanitizerMain <输出目录> <jar...>");
        }
        final Path outputDir = Path.of(args[0]);
        Files.createDirectories(outputDir);

        int totalPatched = 0;
        for (int i = 1; i < args.length; i++) {
            final Path input = Path.of(args[i]);
            final Path output = outputDir.resolve(input.getFileName().toString());
            totalPatched += sanitizeJar(input, output);
        }
        if (totalPatched == 0) {
            System.out.println("[JarSanitizer] 未发现非法成员名，jar 原样复制（根因可能已修复，可考虑移除本工具）");
        }
    }

    private static int sanitizeJar(final Path input, final Path output) throws IOException {
        int patchedClasses = 0;
        try (ZipFile zip = new ZipFile(input.toFile());
             OutputStream fileOut = Files.newOutputStream(output);
             ZipOutputStream out = new ZipOutputStream(fileOut)) {
            final Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                out.putNextEntry(new ZipEntry(entry.getName()));
                try (InputStream in = zip.getInputStream(entry)) {
                    final byte[] content = in.readAllBytes();
                    if (entry.getName().endsWith(".class") && sanitizeClass(content, entry.getName())) {
                        patchedClasses++;
                    }
                    out.write(content);
                }
                out.closeEntry();
            }
        }
        if (patchedClasses > 0) {
            System.out.println("[JarSanitizer] " + input.getFileName() + ": 修补类 " + patchedClasses + " 个 -> " + output);
        }
        return patchedClasses;
    }

    /**
     * 扫描 class 字节，将「名字位置」（NameAndType.name_index、field_info/method_info
     * .name_index）引用的 Utf8 常量中的非法字符原位替换为 '_'。
     *
     * @param classBytes 可读写的 class 字节数组（原位修改）
     * @return 是否有改动
     */
    static boolean sanitizeClass(final byte[] classBytes, final String classNameForLog) {
        final Cursor cursor = new Cursor(classBytes);
        // 注意字面量后缀 L：0xCAFEBABE 作为 int 是负数，与 long 返回值比较会永远不等
        if (cursor.u4() != 0xCAFEBABEL) {
            return false;
        }
        cursor.u2(); // minor
        cursor.u2(); // major
        final int cpCount = cursor.u2();
        // 记录每个 Utf8 常量的负载区间
        final int[] utf8Offset = new int[cpCount];
        final int[] utf8Length = new int[cpCount];
        // 名字位置引用的常量索引
        final Set<Integer> namePositionIndices = new HashSet<>();

        for (int i = 1; i < cpCount; i++) {
            final int tag = cursor.u1();
            switch (tag) {
                case 1 -> { // Utf8
                    final int len = cursor.u2();
                    utf8Offset[i] = cursor.pos();
                    utf8Length[i] = len;
                    cursor.skip(len);
                }
                case 7, 8, 16, 19, 20 -> cursor.skip(2); // Class/String/MethodType/Module/Package
                case 9, 10, 11, 17, 18 -> cursor.skip(4); // refs（name 经 NameAndType 间接，不直接引用）
                case 12 -> { // NameAndType: name_index 是名字位置
                    namePositionIndices.add(cursor.u2());
                    cursor.skip(2); // descriptor_index
                }
                case 3, 4 -> cursor.skip(4); // Integer/Float
                case 5, 6 -> { // Long/Double 占两槽
                    cursor.skip(8);
                    i++;
                }
                case 15 -> cursor.skip(3); // MethodHandle
                default -> throw new IllegalStateException(
                        "未知常量池 tag " + tag + " @ " + classNameForLog);
            }
        }

        // 类级结构：access_flags + this_class + super_class（6 字节），随后 interfaces
        cursor.skip(6);
        cursor.skip(2 * cursor.u2());
        // field_info / method_info 的 name_index 也是名字位置
        readMembers(cursor, namePositionIndices); // fields
        readMembers(cursor, namePositionIndices); // methods

        // 执行原位替换
        boolean changed = false;
        for (final int index : namePositionIndices) {
            if (index <= 0 || index >= cpCount) {
                continue;
            }
            final int offset = utf8Offset[index];
            final int length = utf8Length[index];
            if (offset == 0) {
                continue;
            }
            // <init>/<clinit> 是合法特殊名，跳过
            if (length >= 6 && classBytes[offset] == '<') {
                continue;
            }
            for (int j = offset; j < offset + length; j++) {
                final byte b = classBytes[j];
                // JVM 非限定名禁止字符：. ; [ / 以及方法名的 < >
                // （此处不区分字段/方法：< > 对字段同样不可能合法出现，统一替换安全）
                if (b == '.' || b == ';' || b == '[' || b == '/' || b == '<' || b == '>') {
                    classBytes[j] = '_';
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** 读取 field_info/method_info 表，收集 name_index 并跳过整个表。 */
    private static void readMembers(final Cursor cursor, final Set<Integer> namePositionIndices) {
        final int count = cursor.u2();
        for (int i = 0; i < count; i++) {
            cursor.skip(2); // access_flags
            namePositionIndices.add(cursor.u2()); // name_index
            cursor.skip(2); // descriptor_index
            final int attrCount = cursor.u2();
            for (int a = 0; a < attrCount; a++) {
                cursor.skip(2); // attribute_name_index
                cursor.skip(cursor.u4()); // attribute payload
            }
        }
    }

    /** class 字节游标（big-endian）。 */
    private static final class Cursor {
        private final byte[] data;
        private int pos;

        private Cursor(final byte[] data) {
            this.data = data;
        }

        int pos() {
            return pos;
        }

        int u1() {
            return data[pos++] & 0xFF;
        }

        int u2() {
            final int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            return v;
        }

        long u4() {
            final long v = ((long) (data[pos] & 0xFF) << 24) | ((long) (data[pos + 1] & 0xFF) << 16)
                    | ((long) (data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFFL);
            pos += 4;
            return v;
        }

        void skip(final int bytes) {
            pos += bytes;
        }

        void skip(final long bytes) {
            pos += (int) bytes;
        }
    }
}
