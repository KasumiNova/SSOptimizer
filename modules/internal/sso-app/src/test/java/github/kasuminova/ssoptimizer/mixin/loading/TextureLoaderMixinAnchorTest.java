package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.asm.render.RenderThreadRedirector;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureLoaderMixinAnchorTest {
    private static final String LWJGL_GL11   = "org/lwjgl/opengl/GL11";
    private static final String BRIDGE_GL11  = "github/kasuminova/ssoptimizer/bridge/opengl/GL11";
    private static final String UPLOAD_DESC  = "(IIIIIIIILjava/nio/ByteBuffer;)V";
    private static final String IMAGE_IO     = "javax/imageio/ImageIO";
    private static final String IMAGE_IO_DESC = "(Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;";

    @Test
    void overwrittenAndRedirectedMethodsExistWithExpectedDescriptors() throws IOException {
        final ClassNode node = parse(readClasspathBytes(GameClassNames.TEXTURE_LOADER));

        assertNotNull(method(node, "convertPixels",
                "(Ljava/awt/image/BufferedImage;Lcom/fs/graphics/TextureObject;)Ljava/nio/ByteBuffer;"));
        assertNotNull(method(node, "textureDimension", "(I)I"));
        assertNotNull(method(node, "loadTexture", "(Ljava/lang/String;)Lcom/fs/graphics/TextureObject;"));
        assertNotNull(method(node, "readImage", "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;"));
    }

    @Test
    void readImageHasSingleImageIoReadCallSite() throws IOException {
        final ClassNode node = parse(readClasspathBytes(GameClassNames.TEXTURE_LOADER));
        final MethodNode readImage = method(node, "readImage", "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;");

        assertEquals(1, countCalls(readImage, IMAGE_IO, "read", IMAGE_IO_DESC));
    }

    @Test
    void directUploadCallSitesExistInOriginalBytecode() throws IOException {
        final ClassNode node = parse(readClasspathBytes(GameClassNames.TEXTURE_LOADER));

        assertEquals(1, countCalls(method(node, "createTextureFromImage",
                "(Ljava/awt/image/BufferedImage;IIII)Lcom/fs/graphics/TextureObject;"), LWJGL_GL11, "glTexImage2D", UPLOAD_DESC));
        final MethodNode loadTextureData = method(node, "loadTextureData",
                "(Lcom/fs/graphics/TextureObject;Ljava/lang/String;IIIIZ)Lcom/fs/graphics/TextureObject;");
        assertEquals(1, countCalls(loadTextureData, LWJGL_GL11, "glTexImage2D", UPLOAD_DESC));
        assertEquals(1, countCalls(loadTextureData, LWJGL_GL11, "glTexSubImage2D", UPLOAD_DESC));
    }

    @Test
    void renderThreadRedirectTurnsUploadsIntoBridgeCallSites() throws IOException {
        final byte[] redirected = RenderThreadRedirector.redirect(
                GameClassNames.TEXTURE_LOADER, readClasspathBytes(GameClassNames.TEXTURE_LOADER));
        final ClassNode node = parse(redirected);

        assertEquals(1, countCalls(method(node, "createTextureFromImage",
                "(Ljava/awt/image/BufferedImage;IIII)Lcom/fs/graphics/TextureObject;"), BRIDGE_GL11, "glTexImage2D", UPLOAD_DESC));
        final MethodNode loadTextureData = method(node, "loadTextureData",
                "(Lcom/fs/graphics/TextureObject;Ljava/lang/String;IIIIZ)Lcom/fs/graphics/TextureObject;");
        assertEquals(1, countCalls(loadTextureData, BRIDGE_GL11, "glTexImage2D", UPLOAD_DESC));
        assertEquals(1, countCalls(loadTextureData, BRIDGE_GL11, "glTexSubImage2D", UPLOAD_DESC));
        assertTrue(countCalls(loadTextureData, LWJGL_GL11, "glTexImage2D", UPLOAD_DESC) == 0,
                "重定向后 loadTextureData 不应残留 LWJGL 的 glTexImage2D 调用");
    }

    private static MethodNode method(final ClassNode node, final String name, final String desc) {
        for (final MethodNode method : node.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) {
                return method;
            }
        }
        throw new AssertionError("TextureLoader 缺少方法 " + name + desc);
    }

    private static int countCalls(final MethodNode method, final String owner, final String name, final String desc) {
        int count = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name) && desc.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static ClassNode parse(final byte[] bytes) {
        final ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] readClasspathBytes(final String internalName) throws IOException {
        final String resource = internalName + ".class";
        try (InputStream in = TextureLoaderMixinAnchorTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "测试 classpath 必须包含 " + resource);
            return in.readAllBytes();
        }
    }
}
