package github.kasuminova.ssoptimizer.asm.loading;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * GL 显存账本埋点（Mixin + ASM 处理器）的字节码锚点核验。
 * <p>
 * 与 NaNGuardMixinInjectionTest 同模式：注入是否生效取决于目标方法签名/字段与真实
 * 字节码一致，用 ASM 逐一解析核验而非源码文本匹配。游戏类（FrameBufferObject，
 * 由 Mixin 埋点）取自测试 classpath 的 named jar；GraphicsLib/BoxUtil 类（由
 * sso-loading 的 asm/loading 下五个 LedgerProcessor 埋点——本环境下 Mixin 无法以
 * 第三方模组类为目标）不在 named jar 中，直接解析游戏 mods 目录的真实模组 jar——
 * 路径不可读时整组用例 assume 跳过（CI 无游戏环境）。
 */
class GlLedgerProcessorAnchorTest {

    private static final Path GFXLIB_JAR = Path.of(
            "/mnt/store/Games/Starsector098-linux/mods/GraphicsLib/jars/Graphics.jar");
    private static final Path BOXUTIL_JAR = Path.of(
            "/mnt/store/Games/Starsector098-linux/mods/BoxUtil/jars/BoxUtilMod.jar");
    /** BoxUtil 后端实现 jar（mod_info.json jars 列表第二项；BUtil_RenderingBuffer 在其中）。 */
    private static final Path BOXUTIL_IMPL_JAR = Path.of(
            "/mnt/store/Games/Starsector098-linux/mods/BoxUtil/jars/backends/BoxUtilImpl.jar");

    // ---------- 游戏 FrameBufferObject ----------

    @Test
    void vanillaFboCreateAndDeleteExist() throws IOException {
        final ClassNode node = readClasspathClass("com/fs/graphics/FrameBufferObject");
        assertNotNull(findMethod(node, "createFramebuffer", "(IIIIZ)Z"),
                "FrameBufferObject.createFramebuffer(IIIIZ)Z 必须存在（账本埋点）");
        assertNotNull(findMethod(node, "deleteFramebuffer", "()V"),
                "FrameBufferObject.deleteFramebuffer()V 必须存在（账本埋点）");
        assertTrue(hasField(node, "fboId", "I"), "FrameBufferObject.fboId 字段必须存在");
        // 计量口径佐证：createFramebuffer 内必须确实分配颜色纹理
        final MethodNode create = findMethod(node, "createFramebuffer", "(IIIIZ)Z");
        assertTrue(countCalls(create, "org/lwjgl/opengl/GL11", "glTexImage2D") >= 1,
                "createFramebuffer 必须含 glTexImage2D 调用");
    }

    // ---------- GraphicsLib ----------

    @Test
    void shaderLibAnchorsExist() throws IOException {
        assumeTrue(Files.isRegularFile(GFXLIB_JAR), "GraphicsLib jar 不可读，跳过: " + GFXLIB_JAR);
        try (ZipFile jar = new ZipFile(GFXLIB_JAR.toFile())) {
            final ClassNode node = readJarClass(jar, "org/dark/shaders/util/ShaderLib.class");
            assertNotNull(findMethod(node, "init", "()V"), "ShaderLib.init()V 必须存在");
            assertNotNull(findMethod(node, "makeFramebuffer", "(IIIII)I"),
                    "ShaderLib.makeFramebuffer(IIIII)I 必须存在");
            assertNotNull(findMethod(node, "getInternalWidth", "()I"),
                    "ShaderLib.getInternalWidth()I 必须存在（LightShader 计量尺寸来源）");
            assertNotNull(findMethod(node, "getInternalHeight", "()I"),
                    "ShaderLib.getInternalHeight()I 必须存在");
            for (final String field : new String[]{"RTTSizeX", "RTTSizeY",
                    "auxiliaryBuffer64Bit", "buffersAllowed", "shadersAllowed",
                    "screenTex", "foregroundBufferTex", "auxiliaryBufferTex"}) {
                assertTrue(hasField(node, field, null), "ShaderLib." + field + " 字段必须存在");
            }
            // 计量口径佐证：init 内 4 处直接 glTexImage2D（screenTex/foreground/aux 两分支）
            final MethodNode init = findMethod(node, "init", "()V");
            assertTrue(countCalls(init, "org/lwjgl/opengl/GL11", "glTexImage2D") >= 4,
                    "ShaderLib.init 必须含 >=4 处 glTexImage2D");
            final MethodNode makeFb = findMethod(node, "makeFramebuffer", "(IIIII)I");
            assertTrue(countCallsContaining(makeFb, "glRenderbufferStorage") >= 1,
                    "makeFramebuffer 必须含 glRenderbufferStorage 调用");
        }
    }

    @Test
    void lightShaderAnchorsExist() throws IOException {
        assumeTrue(Files.isRegularFile(GFXLIB_JAR), "GraphicsLib jar 不可读，跳过: " + GFXLIB_JAR);
        try (ZipFile jar = new ZipFile(GFXLIB_JAR.toFile())) {
            final ClassNode node = readJarClass(jar, "org/dark/shaders/light/LightShader.class");
            final MethodNode ctor = findMethod(node, "<init>", "()V");
            assertNotNull(ctor, "LightShader 无参构造器必须存在");
            assertTrue(countCalls(ctor, "org/lwjgl/opengl/GL11", "glTexImage2D") >= 4,
                    "LightShader 构造器必须含 >=4 处 glTexImage2D");
            final MethodNode destroy = findMethod(node, "destroy", "()V");
            assertNotNull(destroy, "LightShader.destroy()V 必须存在（对称移除锚点）");
            assertTrue(countCalls(destroy, "org/lwjgl/opengl/GL11", "glDeleteTextures") >= 5,
                    "destroy 必须删除 >=5 张纹理（与构造计量对称）");
            for (final String field : new String[]{"lightTex", "normalTex", "hdrTex",
                    "hdrTex2", "hdrTex3", "bloomMips"}) {
                assertTrue(hasField(node, field, "I"), "LightShader." + field + " 字段必须存在");
            }
        }
    }

    // ---------- BoxUtil ----------

    @Test
    void boxRenderingBufferAnchorsExist() throws IOException {
        assumeTrue(Files.isRegularFile(BOXUTIL_IMPL_JAR),
                "BoxUtil 后端 jar 不可读，跳过: " + BOXUTIL_IMPL_JAR);
        try (ZipFile jar = new ZipFile(BOXUTIL_IMPL_JAR.toFile())) {
            final ClassNode node = readJarClass(jar,
                    "org/boxutil/backends/buffer/BUtil_RenderingBuffer.class");
            final MethodNode ctor = findMethod(node, "<init>", "()V");
            assertNotNull(ctor, "BUtil_RenderingBuffer 无参构造器必须存在");
            assertTrue(countCalls(ctor, "org/lwjgl/opengl/GL42", "glTexStorage2D") >= 2,
                    "构造器必须含 >=2 处 glTexStorage2D（附件纹理 + bloom 链）");
            assertTrue(countCallsContaining(ctor, "glRenderbufferStorage") >= 1,
                    "构造器必须含 glRenderbufferStorage 调用");
            assertTrue(hasField(node, "texID", "[[I"), "texID 字段必须存在");
            assertTrue(hasField(node, "scaleSize", "[[I"), "scaleSize 字段必须存在");
            assertTrue(hasField(node, "bloomPingPongTex", "[I"), "bloomPingPongTex 字段必须存在");
            assertTrue(hasField(node, "finished", "[Z"), "finished 字段必须存在");
            assertTrue(hasField(node, "currLayerCount", "B"), "currLayerCount 字段必须存在");
            assertTrue(hasField(node, "RBO", "I"), "RBO 字段必须存在");
            assertTrue(hasField(node, "_INTERNAL_FORMAT", "[[I"),
                    "_INTERNAL_FORMAT 静态字段必须存在");
        }
    }

    @Test
    void publicFboAnchorsExist() throws IOException {
        assumeTrue(Files.isRegularFile(BOXUTIL_JAR), "BoxUtil jar 不可读，跳过: " + BOXUTIL_JAR);
        try (ZipFile jar = new ZipFile(BOXUTIL_JAR.toFile())) {
            final ClassNode node = readJarClass(jar,
                    "org/boxutil/units/standard/misc/PublicFBO.class");
            final MethodNode ctor = findMethod(node, "<init>", "()V");
            assertNotNull(ctor, "PublicFBO 无参构造器必须存在");
            assertTrue(countCalls(ctor, "org/lwjgl/opengl/GL11", "glTexImage2D") >= 1,
                    "构造器必须含 glTexImage2D 调用");
            assertTrue(countCallsContaining(ctor, "glRenderbufferStorage") >= 1,
                    "构造器必须含 glRenderbufferStorage 调用");
            assertNotNull(findMethod(node, "delete", "()V"),
                    "PublicFBO.delete()V 必须存在（对称移除锚点）");
            assertTrue(hasField(node, "texID", "[I"), "texID 字段必须存在");
            assertTrue(hasField(node, "RBO", "I"), "RBO 字段必须存在");
            assertTrue(hasField(node, "finished", "Z"), "finished 字段必须存在");
            assertTrue(hasField(node, "FORMAT", "[[I"), "FORMAT 静态字段必须存在");

            // 尺寸来源佐证：构造器必须调用 ShaderCore.getScreenScaleWidth/Height
            final ClassNode core = readJarClass(jar, "org/boxutil/manager/ShaderCore.class");
            assertNotNull(findMethod(core, "getScreenScaleWidth", "()I"),
                    "ShaderCore.getScreenScaleWidth()I 必须存在（尺寸缓存埋点）");
            assertNotNull(findMethod(core, "getScreenScaleHeight", "()I"),
                    "ShaderCore.getScreenScaleHeight()I 必须存在");
            assertTrue(countCalls(ctor, "org/boxutil/manager/ShaderCore",
                    "getScreenScaleWidth") >= 1, "PublicFBO 构造器必须调用 getScreenScaleWidth");
        }
    }

    // ---------- 工具 ----------

    private static ClassNode readClasspathClass(final String slashName) throws IOException {
        try (InputStream in = GlLedgerProcessorAnchorTest.class.getClassLoader()
                .getResourceAsStream(slashName + ".class")) {
            assertNotNull(in, "测试 classpath 必须包含游戏类: " + slashName);
            final ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static ClassNode readJarClass(final ZipFile jar, final String entryName)
            throws IOException {
        final var entry = jar.getEntry(entryName);
        assertNotNull(entry, "模组 jar 必须包含类: " + entryName);
        try (InputStream in = jar.getInputStream(entry)) {
            final ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static MethodNode findMethod(final ClassNode node, final String name,
                                         final String desc) {
        for (final MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        return null;
    }

    private static boolean hasField(final ClassNode node, final String name, final String desc) {
        for (final FieldNode field : node.fields) {
            if (field.name.equals(name) && (desc == null || field.desc.equals(desc))) {
                return true;
            }
        }
        return false;
    }

    private static int countCalls(final MethodNode method, final String owner,
                                  final String name) {
        int count = 0;
        for (final var insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name)) {
                count++;
            }
        }
        return count;
    }

    /** 统计任意 owner 下方法名含指定子串的调用数（GL30/ARB/EXT 三后端分支共用）。 */
    private static int countCallsContaining(final MethodNode method, final String namePart) {
        int count = 0;
        for (final var insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.contains(namePart)) {
                count++;
            }
        }
        return count;
    }
}
