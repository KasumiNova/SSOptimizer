package github.kasuminova.ssoptimizer.mixin.ime;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFieldImeMixinAnchorTest {
    private static final String ACTION_LISTENER_DESC = "Lcom/fs/starfarer/ui/ActionListener;";
    private static final String STRING_DESC = "Ljava/lang/String;";

    @Test
    void textFieldImplementationContainsConstructorAndFocusReturnAnchors() throws IOException {
        final ClassNode node = readClasspathClass(GameClassNames.TEXT_FIELD_IMPL);

        assertReturnAnchor(node, "<init>",
                "(" + STRING_DESC + STRING_DESC + ACTION_LISTENER_DESC + ")V", Opcodes.RETURN);
        assertReturnAnchor(node, "<init>",
                "(" + STRING_DESC + STRING_DESC + "Z" + ACTION_LISTENER_DESC + ")V", Opcodes.RETURN);
        assertReturnAnchor(node, GameMixinSignatures.TextFieldIme.GRAB_FOCUS,
                GameMixinSignatures.TextFieldIme.GRAB_FOCUS_DESC, Opcodes.RETURN);
        assertReturnAnchor(node, GameMixinSignatures.TextFieldIme.RELEASE_FOCUS,
                GameMixinSignatures.TextFieldIme.RELEASE_FOCUS_DESC, Opcodes.RETURN);
    }

    @Test
    void tooltipTextFieldFactoriesContainReturnAnchors() throws IOException {
        final ClassNode node = readClasspathClass(GameClassNames.STANDARD_TOOLTIP_V2_EXPANDABLE);
        final String returnDesc = GameMixinSignatures.TextFieldIme.TEXT_FIELD_API_DESC;

        assertReturnAnchor(node, GameMixinSignatures.TextFieldIme.ADD_TEXT_FIELD,
                "(FF)" + returnDesc, Opcodes.ARETURN);
        assertReturnAnchor(node, GameMixinSignatures.TextFieldIme.ADD_TEXT_FIELD,
                "(F" + STRING_DESC + "F)" + returnDesc, Opcodes.ARETURN);
        assertReturnAnchor(node, GameMixinSignatures.TextFieldIme.ADD_TEXT_FIELD,
                "(FF" + STRING_DESC + "F)" + returnDesc, Opcodes.ARETURN);
    }

    @Test
    void settingsTextFieldFactoryContainsReturnAnchor() throws IOException {
        final ClassNode node = readClasspathClass(GameClassNames.STARFARER_SETTINGS_TEXT_FIELD_OWNER);

        assertReturnAnchor(node, GameMixinSignatures.TextFieldIme.CREATE_TEXT_FIELD,
                "(" + STRING_DESC + STRING_DESC + ")"
                        + GameMixinSignatures.TextFieldIme.TEXT_FIELD_API_DESC,
                Opcodes.ARETURN);
    }

    private static void assertReturnAnchor(final ClassNode node, final String name, final String desc,
                                           final int returnOpcode) {
        MethodNode target = null;
        for (final MethodNode method : node.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) {
                target = method;
                break;
            }
        }
        assertNotNull(target, node.name + "." + name + desc + " 必须存在（RETURN 注入目标）");

        boolean returnFound = false;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == returnOpcode) {
                returnFound = true;
                break;
            }
        }
        assertTrue(returnFound, node.name + "." + name + desc + " 必须包含 RETURN 注入点");
    }

    private static ClassNode readClasspathClass(final String internalName) throws IOException {
        final String resource = internalName + ".class";
        try (InputStream in = TextFieldImeMixinAnchorTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "测试 classpath 必须包含 " + resource);
            final ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
}
