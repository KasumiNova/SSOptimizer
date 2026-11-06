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

/** 等长修补非法成员名，避免 ASM 重编码混淆类的 StackMapTable。 */
public final class JarSanitizerMain {
    private JarSanitizerMain() {
    }

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

    static boolean sanitizeClass(final byte[] classBytes, final String classNameForLog) {
        final Cursor cursor = new Cursor(classBytes);
        // 0xCAFEBABE 作为 int 为负数，必须与 long 返回值比较。
        if (cursor.u4() != 0xCAFEBABEL) {
            return false;
        }
        cursor.u2();
        cursor.u2();
        final int cpCount = cursor.u2();
        final int[] utf8Offset = new int[cpCount];
        final int[] utf8Length = new int[cpCount];
        final Set<Integer> namePositionIndices = new HashSet<>();

        for (int i = 1; i < cpCount; i++) {
            final int tag = cursor.u1();
            switch (tag) {
                case 1 -> {
                    final int len = cursor.u2();
                    utf8Offset[i] = cursor.pos();
                    utf8Length[i] = len;
                    cursor.skip(len);
                }
                case 7, 8, 16, 19, 20 -> cursor.skip(2);
                case 9, 10, 11, 17, 18 -> cursor.skip(4);
                case 12 -> {
                    namePositionIndices.add(cursor.u2());
                    cursor.skip(2);
                }
                case 3, 4 -> cursor.skip(4);
                case 5, 6 -> { // Long/Double 占两个常量池槽位。
                    cursor.skip(8);
                    i++;
                }
                case 15 -> cursor.skip(3);
                default -> throw new IllegalStateException(
                        "未知常量池 tag " + tag + " @ " + classNameForLog);
            }
        }

        cursor.skip(6);
        cursor.skip(2 * cursor.u2());
        readMembers(cursor, namePositionIndices);
        readMembers(cursor, namePositionIndices);

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
            if (length >= 6 && classBytes[offset] == '<') {
                continue;
            }
            for (int j = offset; j < offset + length; j++) {
                final byte b = classBytes[j];
                if (b == '.' || b == ';' || b == '[' || b == '/' || b == '<' || b == '>') {
                    classBytes[j] = '_';
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static void readMembers(final Cursor cursor, final Set<Integer> namePositionIndices) {
        final int count = cursor.u2();
        for (int i = 0; i < count; i++) {
            cursor.skip(2);
            namePositionIndices.add(cursor.u2());
            cursor.skip(2);
            final int attrCount = cursor.u2();
            for (int a = 0; a < attrCount; a++) {
                cursor.skip(2);
                cursor.skip(cursor.u4());
            }
        }
    }

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
