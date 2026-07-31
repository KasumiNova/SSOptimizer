package github.kasuminova.ssoptimizer.mapping.gen;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 结构指纹计算。
 * <p>
 * 类指纹输入：父类 + 接口（排序）+ 字段描述符多重集（排序）+ 方法（描述符 + access）多重集
 * （排序，剔除 {@code <clinit>}）；成员指纹输入：成员类别 + 描述符（方法另含 access）。
 * 对规范化文本做 SHA-256 并截取前 8 个十六进制字符，作为占位名与跨平台对齐锚点。
 * 同一输入两次计算结果必然一致，生成器整体确定性由该保证与排序规则共同构成。
 */
public final class StructuralFingerprint {
    /** 指纹十六进制长度。 */
    public static final int HASH_LENGTH = 8;

    private StructuralFingerprint() {
    }

    /**
     * 计算类结构指纹。
     *
     * @param classStructure 类结构
     * @return 8 位十六进制指纹
     */
    public static String ofClass(ClassStructure classStructure) {
        List<String> lines = new ArrayList<>();
        lines.add("super=" + classStructure.superName());

        List<String> interfaces = new ArrayList<>(classStructure.interfaces());
        Collections.sort(interfaces);
        for (String interfaceName : interfaces) {
            lines.add("interface=" + interfaceName);
        }

        List<String> fieldDescs = new ArrayList<>();
        for (ClassStructure.Member field : classStructure.fields()) {
            fieldDescs.add(field.desc());
        }
        Collections.sort(fieldDescs);
        for (String desc : fieldDescs) {
            lines.add("field=" + desc);
        }

        List<String> methodSignatures = new ArrayList<>();
        for (ClassStructure.Member method : classStructure.methods()) {
            if ("<clinit>".equals(method.name())) {
                continue;
            }
            methodSignatures.add(method.desc() + '@' + method.access());
        }
        Collections.sort(methodSignatures);
        for (String signature : methodSignatures) {
            lines.add("method=" + signature);
        }

        return sha256Hex8(String.join("\n", lines));
    }

    /**
     * 计算字段结构指纹。
     *
     * @param field 字段成员
     * @return 8 位十六进制指纹
     */
    public static String ofField(ClassStructure.Member field) {
        return sha256Hex8("field\n" + field.desc());
    }

    /**
     * 计算方法结构指纹。
     *
     * @param method 方法成员
     * @return 8 位十六进制指纹
     */
    public static String ofMethod(ClassStructure.Member method) {
        return sha256Hex8("method\n" + method.desc() + '\n' + method.access());
    }

    private static String sha256Hex8(String input) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(HASH_LENGTH);
        for (int i = 0; i < HASH_LENGTH / 2; i++) {
            builder.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
            builder.append(Character.forDigit(hash[i] & 0xF, 16));
        }
        return builder.toString();
    }
}
