package github.kasuminova.ssoptimizer.bootstrap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RealBytecodeIntegrationTest {

    private byte[] loadClassBytes(String internalName) {
        return RuntimeViewFixtures.readRuntimeNamedBytes(internalName);
    }

    @Test
    void remappedTextureLoaderUsesSemanticallyCorrectImageDimensionSetters() {
        byte[] original = loadClassBytes("com/fs/graphics/TextureLoader");
        assumeTrue(original != null, "TextureLoader not on classpath");

        boolean[] widthMappedToWidthSetter = {false};
        boolean[] heightMappedToHeightSetter = {false};
        String textureObjectOwner = "com/fs/graphics/TextureObject";

        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    private String lastBufferedImageDimension;

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean itf) {
                        if ("java/awt/image/BufferedImage".equals(owner)
                                && "()I".equals(methodDesc)
                                && ("getWidth".equals(methodName) || "getHeight".equals(methodName))) {
                            lastBufferedImageDimension = methodName;
                            return;
                        }

                        if (textureObjectOwner.equals(owner)
                                && "(I)V".equals(methodDesc)
                                && lastBufferedImageDimension != null) {
                            if ("getWidth".equals(lastBufferedImageDimension) && "setImageWidth".equals(methodName)) {
                                widthMappedToWidthSetter[0] = true;
                            }
                            if ("getHeight".equals(lastBufferedImageDimension) && "setImageHeight".equals(methodName)) {
                                heightMappedToHeightSetter[0] = true;
                            }
                            lastBufferedImageDimension = null;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(widthMappedToWidthSetter[0],
                "Remapped TextureLoader should route BufferedImage.getWidth() into TextureObject.setImageWidth(int)");
        assertTrue(heightMappedToHeightSetter[0],
                "Remapped TextureLoader should route BufferedImage.getHeight() into TextureObject.setImageHeight(int)");
    }

    @Test
    void launcherDirectStartRewritesRealLauncherConstructorBytecode() {
        var processor = new github.kasuminova.ssoptimizer.asm.launcher.LauncherDirectStartProcessor();
        byte[] original = loadClassBytes(github.kasuminova.ssoptimizer.asm.launcher.LauncherDirectStartProcessor.TARGET_CLASS);
        assumeTrue(original != null, "StarfarerLauncher not on classpath");

        byte[] rewritten = assertDoesNotThrow(() -> processor.process(original),
                "Launcher direct-start processor should handle real StarfarerLauncher bytecode");
        assertNotNull(rewritten, "Processor should rewrite the launcher constructor");

        int helperCalls = countHelperCalls(rewritten,
                github.kasuminova.ssoptimizer.asm.launcher.LauncherDirectStartProcessor.HELPER_OWNER,
                github.kasuminova.ssoptimizer.asm.launcher.LauncherDirectStartProcessor.HELPER_METHOD,
                github.kasuminova.ssoptimizer.asm.launcher.LauncherDirectStartProcessor.HELPER_DESC);
        assertTrue(helperCalls > 0,
                "Rewritten launcher constructor should call LauncherDirectStarter.tryDirectStart");
    }

    private int countHelperCalls(byte[] classBytes, String owner, String methodName, String methodDesc) {
        int[] count = {0};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String instructionOwner, String instructionName,
                                                String instructionDesc, boolean itf) {
                        if (owner.equals(instructionOwner)
                                && methodName.equals(instructionName)
                                && methodDesc.equals(instructionDesc)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, 0);
        return count[0];
    }
}
