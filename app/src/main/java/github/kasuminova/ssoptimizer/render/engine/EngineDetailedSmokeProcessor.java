package github.kasuminova.ssoptimizer.render.engine;

import github.kasuminova.ssoptimizer.agent.AsmClassProcessor;
import org.objectweb.asm.*;

/**
 * Rewrites {@code DetailedSmokeParticle.preBatch()}, {@code render()}, and
 * {@code postBatch()} to use {@link ParticleBatchHelper} for batched
 * rendering via {@code glDrawArrays} instead of per-particle immediate mode.
 * <p>
 * Original per-particle: 13 GL calls (color, pushMatrix, translatef, rotatef,
 * begin, 4×texCoord2f, 4×vertex2f, end, popMatrix).
 * <p>
 * New flow: preBatch sets up GL state + beginBatch(), each render() buffers
 * rotated vertex data in Java (zero GL calls), postBatch flushes with
 * one glDrawArrays.
 */
public final class EngineDetailedSmokeProcessor implements AsmClassProcessor {
    static final String TARGET_CLASS =
            "com/fs/starfarer/renderers/fx/DetailedSmokeParticle";

    static final String HELPER_OWNER =
            "github/kasuminova/ssoptimizer/render/engine/ParticleBatchHelper";

    private static final String PARTICLE_CLASS = TARGET_CLASS;
    private static final String TEXTURE_CLASS  = "com/fs/graphics/Object";
    private static final String TEXTURE_DESC   = "L" + TEXTURE_CLASS + ";";
    private static final String COLOR_CLASS    = "java/awt/Color";
    private static final String COLOR_DESC     = "L" + COLOR_CLASS + ";";
    private static final String GL11_OWNER     = "org/lwjgl/opengl/GL11";

    private static final String BIND_METHOD = "\u00D800000";

    @Override
    public byte[] process(byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, sig, ex);
                if ("preBatch".equals(name) && "()V".equals(desc)) {
                    modified[0] = true;
                    return new PreBatchReplacer(delegate);
                }
                if ("render".equals(name) && "()V".equals(desc)) {
                    modified[0] = true;
                    return new RenderReplacer(delegate);
                }
                if ("postBatch".equals(name) && "()V".equals(desc)) {
                    modified[0] = true;
                    return new PostBatchReplacer(delegate);
                }
                return delegate;
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    // ── preBatch replacement ────────────────────────────────────

    static final class PreBatchReplacer extends MethodVisitor {
        private final MethodVisitor target;

        PreBatchReplacer(MethodVisitor target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public void visitCode() {
            target.visitCode();
            emitBody();
        }

        @Override
        public void visitMaxs(int m, int l) {
            target.visitMaxs(0, 0);
        }

        @Override
        public void visitEnd() {
            target.visitEnd();
        }

        @Override
        public AnnotationVisitor visitAnnotation(String d, boolean v) {
            return target.visitAnnotation(d, v);
        }

        private void emitBody() {
            // glEnable(GL_TEXTURE_2D = 3553)
            target.visitIntInsn(Opcodes.SIPUSH, 3553);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, GL11_OWNER, "glEnable", "(I)V", false);

            // glEnable(GL_BLEND = 3042)
            target.visitIntInsn(Opcodes.SIPUSH, 3042);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, GL11_OWNER, "glEnable", "(I)V", false);

            // glBlendFunc(GL_SRC_ALPHA = 770, GL_ONE_MINUS_SRC_ALPHA = 771)
            target.visitIntInsn(Opcodes.SIPUSH, 770);
            target.visitIntInsn(Opcodes.SIPUSH, 771);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, GL11_OWNER, "glBlendFunc", "(II)V", false);

            // DetailedSmokeParticle.texture.bind() (static field)
            target.visitFieldInsn(Opcodes.GETSTATIC, PARTICLE_CLASS, "texture", TEXTURE_DESC);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TEXTURE_CLASS, BIND_METHOD, "()V", false);

            // ParticleBatchHelper.beginSmokeBatch()
            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER, "beginSmokeBatch", "()V", false);

            target.visitInsn(Opcodes.RETURN);
        }
    }

    // ── render replacement ──────────────────────────────────────

    static final class RenderReplacer extends MethodVisitor {
        private final MethodVisitor target;

        RenderReplacer(MethodVisitor target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public void visitCode() {
            target.visitCode();
            emitBody();
        }

        @Override
        public void visitMaxs(int m, int l) {
            target.visitMaxs(0, 0);
        }

        @Override
        public void visitEnd() {
            target.visitEnd();
        }

        @Override
        public AnnotationVisitor visitAnnotation(String d, boolean v) {
            return target.visitAnnotation(d, v);
        }

        private void emitBody() {
            // --- Push 10 parameters for ParticleBatchHelper.addSmokeParticle ---

            // r = this.color.getRed()
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "color", COLOR_DESC);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COLOR_CLASS, "getRed", "()I", false);

            // g = this.color.getGreen()
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "color", COLOR_DESC);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COLOR_CLASS, "getGreen", "()I", false);

            // b = this.color.getBlue()
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "color", COLOR_DESC);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COLOR_CLASS, "getBlue", "()I", false);

            // a = (int)((float)this.color.getAlpha() * getBrightness())
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "color", COLOR_DESC);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COLOR_CLASS, "getAlpha", "()I", false);
            target.visitInsn(Opcodes.I2F);
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PARTICLE_CLASS,
                    "getBrightness", "()F", false);
            target.visitInsn(Opcodes.FMUL);
            target.visitInsn(Opcodes.F2I);

            // x = getX()
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PARTICLE_CLASS, "getX", "()F", false);

            // y = getY()
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PARTICLE_CLASS, "getY", "()F", false);

            // angle = getAngle()
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PARTICLE_CLASS, "getAngle", "()F", false);

            // this.offsetX
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "offsetX", "F");

            // this.offsetY
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "offsetY", "F");

            // this.size
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "size", "F");

            // ParticleBatchHelper.addSmokeParticle(r, g, b, a, x, y, angle, offsetX, offsetY, size)
            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER,
                    "addSmokeParticle", "(IIIIFFFFFF)V", false);

            target.visitInsn(Opcodes.RETURN);
        }
    }

    // ── postBatch replacement ───────────────────────────────────

    static final class PostBatchReplacer extends MethodVisitor {
        private final MethodVisitor target;

        PostBatchReplacer(MethodVisitor target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public void visitCode() {
            target.visitCode();
            emitBody();
        }

        @Override
        public void visitMaxs(int m, int l) {
            target.visitMaxs(0, 0);
        }

        @Override
        public void visitEnd() {
            target.visitEnd();
        }

        @Override
        public AnnotationVisitor visitAnnotation(String d, boolean v) {
            return target.visitAnnotation(d, v);
        }

        private void emitBody() {
            // ParticleBatchHelper.flushSmokeBatch()
            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER, "flushSmokeBatch", "()V", false);

            // glDisable(GL_TEXTURE_2D = 3553)
            target.visitIntInsn(Opcodes.SIPUSH, 3553);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, GL11_OWNER, "glDisable", "(I)V", false);

            target.visitInsn(Opcodes.RETURN);
        }
    }
}
