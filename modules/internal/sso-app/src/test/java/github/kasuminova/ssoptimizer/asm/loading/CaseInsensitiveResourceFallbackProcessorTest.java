package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CaseInsensitiveResourceFallbackProcessor} 的真实游戏字节码驱动核验。
 * <p>
 * 包装方法的隔离状态捕获（filter/suppress 先于 try）、内部实现重命名、
 * 兜底调用签名，任一漂移在运行期表现为 VerifyError/NoSuchMethodError 或
 * 跨模组资源泄漏——构建期用 ASM 解析产物结构直接核验。
 */
class CaseInsensitiveResourceFallbackProcessorTest implements Opcodes {

    private static final String THREAD_STATE_OWNER =
            "github/kasuminova/ssoptimizer/common/loading/ResourceLoaderThreadState";
    private static final String TRY_RESOLVE_DESC =
            "(Ljava/lang/String;Ljava/lang/RuntimeException;Ljava/lang/String;Z)Ljava/io/InputStream;";

    @Test
    void wrapperCapturesIsolationStateBeforeTryAndDelegatesToFourArgFallback() throws IOException {
        final byte[] rewritten = new CaseInsensitiveResourceFallbackProcessor()
                .process(readGameClass(GameClassNames.RESOURCE_LOADER));
        assertNotNull(rewritten, "ResourceLoader 必须被处理器改写");

        final ClassNode node = new ClassNode();
        new ClassReader(rewritten).accept(node, 0);

        // 原始方法体必须重命名为 private synthetic 内部实现
        final MethodNode impl = findMethod(node, CaseInsensitiveResourceFallbackProcessor.IMPL_METHOD,
                CaseInsensitiveResourceFallbackProcessor.TARGET_DESC);
        assertTrue((impl.access & ACC_PRIVATE) != 0 && (impl.access & ACC_SYNTHETIC) != 0,
                "内部实现方法必须为 private synthetic");

        final MethodNode wrapper = findMethod(node, GameMemberNames.ResourceLoader.OPEN_STREAM,
                CaseInsensitiveResourceFallbackProcessor.TARGET_DESC);
        int filterCapture = -1;
        int suppressCapture = -1;
        int implCall = -1;
        int fallbackCall = -1;
        for (int i = 0; i < wrapper.instructions.size(); i++) {
            final AbstractInsnNode insn = wrapper.instructions.get(i);
            if (!(insn instanceof MethodInsnNode call)) {
                continue;
            }
            if (THREAD_STATE_OWNER.equals(call.owner) && "getSourceFilter".equals(call.name)) {
                filterCapture = i;
            } else if (THREAD_STATE_OWNER.equals(call.owner) && "isSuppressCustomResources".equals(call.name)) {
                suppressCapture = i;
            } else if (GameClassNames.RESOURCE_LOADER.equals(call.owner)
                    && CaseInsensitiveResourceFallbackProcessor.IMPL_METHOD.equals(call.name)) {
                implCall = i;
            } else if (CaseInsensitiveResourceFallbackProcessor.HELPER_OWNER.equals(call.owner)
                    && "tryResolve".equals(call.name)) {
                fallbackCall = i;
                assertEquals(TRY_RESOLVE_DESC, call.desc,
                        "兜底调用必须为带隔离状态的四参签名");
            }
        }

        assertTrue(filterCapture >= 0, "包装方法必须捕获 source filter");
        assertTrue(suppressCapture >= 0, "包装方法必须捕获 suppress 标记");
        assertTrue(implCall > 0, "包装方法必须调用重命名后的内部实现");
        assertTrue(fallbackCall > 0, "包装方法必须调用兜底解析");
        assertTrue(filterCapture < implCall && suppressCapture < implCall,
                "隔离状态捕获必须先于 try 块（openResource 的消费在调用链内部）");
        assertTrue(fallbackCall > implCall, "兜底调用必须在内部实现之后（catch 块）");
    }

    private static MethodNode findMethod(final ClassNode node, final String name, final String desc) {
        for (final MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        throw new AssertionError("改写产物缺少方法 " + name + desc);
    }

    private static byte[] readGameClass(final String slashClassName) throws IOException {
        final String resource = slashClassName + ".class";
        try (InputStream in = CaseInsensitiveResourceFallbackProcessorTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "测试 classpath 必须包含游戏类: " + resource);
            return in.readAllBytes();
        }
    }
}
