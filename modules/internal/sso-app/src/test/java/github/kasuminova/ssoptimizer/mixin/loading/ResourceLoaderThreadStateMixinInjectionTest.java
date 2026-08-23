package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ResourceLoader / SettingsAPI 实现类线程封闭化的注入锚点唯一性核验。
 * <p>
 * 游戏类无法脱离引擎实例化，@Redirect 是否生效取决于锚点指令在游戏字节码中
 * 方法内唯一；用 ASM 解析测试 classpath 上的真实游戏字节码逐一核验（非源码文本匹配），
 * 任一锚点漂移都会在构建期暴露而非运行时静默失败。
 */
class ResourceLoaderThreadStateMixinInjectionTest {

    private static final String OPEN_RESOURCE_DESC = "(Ljava/lang/String;Z)Ljava/io/InputStream;";
    private static final String SETTINGS_API_IMPL =
            "com/fs/starfarer/settings/StarfarerSettings$SettingsTextFieldFactory";

    @Test
    void openResourceResourcePathReadAndConsumeWriteAnchorsUnique() throws IOException {
        // ResourceLoaderMixin.ssoptimizer$getSourceFilter / ssoptimizer$setSourceFilter 的锚点
        MethodNode method = findMethod(GameClassNames.RESOURCE_LOADER, "openResource", OPEN_RESOURCE_DESC);
        assertEquals(1, countFieldInsn(method, Opcodes.GETFIELD,
                        GameClassNames.RESOURCE_LOADER, "resourcePath", "Ljava/lang/String;"),
                "openResource 内 GETFIELD resourcePath（filter 读取）必须唯一");
        assertEquals(1, countFieldInsn(method, Opcodes.PUTFIELD,
                        GameClassNames.RESOURCE_LOADER, "resourcePath", "Ljava/lang/String;"),
                "openResource 内 PUTFIELD resourcePath（消费置 null）必须唯一");
    }

    @Test
    void setResourcePathWriteAnchorUnique() throws IOException {
        // ResourceLoaderMixin.ssoptimizer$setSourceFilter 的第二处锚点
        MethodNode method = findMethod(GameClassNames.RESOURCE_LOADER,
                "setResourcePath", "(Ljava/lang/String;)V");
        assertEquals(1, countFieldInsn(method, Opcodes.PUTFIELD,
                        GameClassNames.RESOURCE_LOADER, "resourcePath", "Ljava/lang/String;"),
                "setResourcePath 内 PUTFIELD resourcePath 必须唯一");
    }

    @Test
    void openResourceSuppressCustomResourcesAnchorsUnique() throws IOException {
        // ResourceLoaderMixin.ssoptimizer$is/setSuppressCustomResources 的锚点
        MethodNode method = findMethod(GameClassNames.RESOURCE_LOADER, "openResource", OPEN_RESOURCE_DESC);
        assertEquals(1, countFieldInsn(method, Opcodes.GETSTATIC,
                        GameClassNames.RESOURCE_LOADER, "suppressCustomResources", "Z"),
                "openResource 内 GETSTATIC suppressCustomResources（读取）必须唯一");
        assertEquals(1, countFieldInsn(method, Opcodes.PUTSTATIC,
                        GameClassNames.RESOURCE_LOADER, "suppressCustomResources", "Z"),
                "openResource 内 PUTSTATIC suppressCustomResources（消费置 false）必须唯一");
    }

    @Test
    void settingsApiImplSuppressWriteAnchorsUnique() throws IOException {
        // StarfarerSettingsApiImplMixin.ssoptimizer$setSuppressCustomResources 的两处锚点
        MethodNode loadCSV = findMethod(SETTINGS_API_IMPL, "loadCSV",
                "(Ljava/lang/String;Z)Lorg/json/JSONArray;");
        assertEquals(1, countFieldInsn(loadCSV, Opcodes.PUTSTATIC,
                        GameClassNames.RESOURCE_LOADER, "suppressCustomResources", "Z"),
                "loadCSV(String,boolean) 内 PUTSTATIC suppressCustomResources 必须唯一");

        MethodNode loadJSON = findMethod(SETTINGS_API_IMPL, "loadJSON",
                "(Ljava/lang/String;Z)Lorg/json/JSONObject;");
        assertEquals(1, countFieldInsn(loadJSON, Opcodes.PUTSTATIC,
                        GameClassNames.RESOURCE_LOADER, "suppressCustomResources", "Z"),
                "loadJSON(String,boolean) 内 PUTSTATIC suppressCustomResources 必须唯一");
    }

    private static int countFieldInsn(final MethodNode method, final int opcode, final String owner,
                                      final String name, final String desc) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field
                    && field.getOpcode() == opcode
                    && field.owner.equals(owner)
                    && field.name.equals(name)
                    && field.desc.equals(desc)) {
                count++;
            }
        }
        return count;
    }

    private static MethodNode findMethod(final String slashClassName, final String name,
                                         final String desc) throws IOException {
        ClassNode node = readClass(slashClassName);
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        throw new AssertionError(slashClassName + "." + name + desc + " 必须存在（线程封闭化注入点）");
    }

    private static ClassNode readClass(final String slashClassName) throws IOException {
        String resource = slashClassName + ".class";
        try (InputStream in = ResourceLoaderThreadStateMixinInjectionTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "测试 classpath 必须包含游戏类: " + resource);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
}
