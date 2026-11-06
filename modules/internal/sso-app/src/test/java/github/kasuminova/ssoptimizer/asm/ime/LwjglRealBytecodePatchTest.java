package github.kasuminova.ssoptimizer.asm.ime;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LwjglRealBytecodePatchTest {
    private static final String LINUX_EVENT = "org/lwjgl/opengl/LinuxEvent";

    @Test
    void linuxDisplayRewriteVerifiesAndInjectsEventHooks() throws IOException {
        final byte[] rewritten = rewrite(LinuxDisplayImeProcessor.TARGET_CLASS, new LinuxDisplayImeProcessor());
        assertVerifies(rewritten);

        final ClassNode node = parse(rewritten);
        final MethodNode processEvents = method(node, "processEvents");
        assertTrue(calls(processEvents, LinuxDisplayImeProcessor.HOOK_OWNER, "onRawXEvent"), "processEvents 必须调用 onRawXEvent");
        assertTrue(calls(processEvents, LinuxDisplayImeProcessor.HOOK_OWNER, "onXEvent"), "processEvents 必须调用 onXEvent");
        assertTrue(calls(processEvents, LinuxDisplayImeProcessor.TARGET_CLASS, LinuxDisplayImeProcessor.CLEAR_EVENT_METHOD),
                "processEvents 必须在钩子要求时调用事件清空辅助方法");

        final MethodNode clear = method(node, LinuxDisplayImeProcessor.CLEAR_EVENT_METHOD);
        assertTrue(calls(clear, LINUX_EVENT, "copyFrom"), "清空辅助方法必须调用 LinuxEvent.copyFrom");
        assertTrue(calls(method(node, "createKeyboard"), LinuxDisplayImeProcessor.HOOK_OWNER, "afterCreateKeyboard"));
    }

    @Test
    void windowsDisplayRewriteVerifiesAndReadsHwndInsideClass() throws IOException {
        final byte[] rewritten = rewrite(WindowsDisplayImeProcessor.TARGET_CLASS, new WindowsDisplayImeProcessor());
        assertVerifies(rewritten);

        final MethodNode createKeyboard = method(parse(rewritten), "createKeyboard");
        assertTrue(calls(createKeyboard, WindowsDisplayImeProcessor.HOOK_OWNER, "afterCreateKeyboard"));
        assertTrue(Arrays.stream(createKeyboard.instructions.toArray())
                        .anyMatch(i -> i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
                                && f.owner.equals(WindowsDisplayImeProcessor.TARGET_CLASS) && f.name.equals("hwnd")),
                "createKeyboard 必须在类内读取 hwnd");
    }

    private static byte[] rewrite(final String internalName, final AsmClassProcessor processor) throws IOException {
        final byte[] original = readLwjglClass(internalName);
        final byte[] rewritten = processor.process(original);
        assertNotNull(rewritten, "处理器应匹配并改写真实类: " + internalName);
        return rewritten;
    }

    private static byte[] readLwjglClass(final String internalName) throws IOException {
        try (InputStream in = LwjglRealBytecodePatchTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, "测试 classpath 上缺少真实 LWJGL 类: " + internalName);
            return in.readAllBytes();
        }
    }

    private static void assertVerifies(final byte[] bytes) {
        final StringWriter out = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(bytes), false, new PrintWriter(out));
        assertTrue(out.toString().isEmpty(), "改写产物必须零校验错误: " + out);
    }

    private static ClassNode parse(final byte[] bytes) {
        final ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(final ClassNode node, final String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("缺少方法: " + name));
    }

    private static boolean calls(final MethodNode method, final String owner, final String name) {
        return Arrays.stream(method.instructions.toArray())
                .anyMatch(i -> i instanceof MethodInsnNode mi && mi.owner.equals(owner) && mi.name.equals(name));
    }
}
