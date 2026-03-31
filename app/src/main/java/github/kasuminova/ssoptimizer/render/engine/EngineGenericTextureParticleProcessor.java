package github.kasuminova.ssoptimizer.render.engine;

import github.kasuminova.ssoptimizer.agent.AsmClassProcessor;
import org.objectweb.asm.*;

/**
 * Rewrites {@code GenericTextureParticle.preBatch()}, {@code render()}, and
 * {@code postBatch()} to use {@link ParticleBatchHelper} for batched
 * rendering via {@code glDrawArrays} instead of per-particle immediate mode.
 * <p>
 * Original flow: each render() calls glBlendFunc + glPushMatrix/Translate/
 * Rotate/PopMatrix + glBegin/End + 4×(texCoord+vertex) per renderCount.
 * <p>
 * New flow: preBatch sets up GL state + beginBatch(), each render() buffers
 * rotated vertex data in Java (one glBlendFunc call only on blend-mode change),
 * postBatch flushes everything with one glDrawArrays.
 */
public final class EngineGenericTextureParticleProcessor implements AsmClassProcessor {
    static final String TARGET_CLASS = "com/fs/graphics/particle/GenericTextureParticle";

    static final String HELPER_OWNER =
            "github/kasuminova/ssoptimizer/render/engine/ParticleBatchHelper";

    private static final String PARTICLE_CLASS = TARGET_CLASS;
    private static final String TEXTURE_CLASS  = "com/fs/graphics/Object";
    private static final String TEXTURE_DESC   = "L" + TEXTURE_CLASS + ";";
    private static final String COLOR_CLASS    = "java/awt/Color";
    private static final String COLOR_DESC     = "L" + COLOR_CLASS + ";";
    private static final String GL11_OWNER     = "org/lwjgl/opengl/GL11";

    private static final String BIND_METHOD = "\u00D800000";
    private static final String ADD_DESC    = "(IIIIIIFFFFFFFFFI)V";

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

            // this.texture.bind() — instance field, not static!
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "texture", TEXTURE_DESC);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TEXTURE_CLASS, BIND_METHOD, "()V", false);

            // ParticleBatchHelper.beginGenericTextureBatch()
            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER, "beginGenericTextureBatch", "()V", false);

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
            // ── Step 1: float brightness = getBrightness(); → local 1 ──
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PARTICLE_CLASS,
                    "getBrightness", "()F", false);
            target.visitVarInsn(Opcodes.FSTORE, 1);

            // ── Step 2: if (brightness >= 1.0f) fullyFadedIn = true ──
            Label skipSet = new Label();
            target.visitVarInsn(Opcodes.FLOAD, 1);
            target.visitInsn(Opcodes.FCONST_1);
            target.visitInsn(Opcodes.FCMPL);
            target.visitJumpInsn(Opcodes.IFLT, skipSet);
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitInsn(Opcodes.ICONST_1);
            target.visitFieldInsn(Opcodes.PUTFIELD, PARTICLE_CLASS, "fullyFadedIn", "Z");
            target.visitLabel(skipSet);

            // ── Step 3: fullBrightnessFraction re-computation ──
            Label skipComplex = new Label();
            Label elseBranch = new Label();

            // if (!fullyFadedIn) skip
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "fullyFadedIn", "Z");
            target.visitJumpInsn(Opcodes.IFEQ, skipComplex);

            // if (fullBrightnessFraction <= 0) skip
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "fullBrightnessFraction", "F");
            target.visitInsn(Opcodes.FCONST_0);
            target.visitInsn(Opcodes.FCMPL);
            target.visitJumpInsn(Opcodes.IFLE, skipComplex);

            // brightness = getAge() / getMaxAge()
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PARTICLE_CLASS, "getAge", "()F", false);
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PARTICLE_CLASS, "getMaxAge", "()F", false);
            target.visitInsn(Opcodes.FDIV);
            target.visitVarInsn(Opcodes.FSTORE, 1);

            // if (brightness > fullBrightnessFraction) goto else
            target.visitVarInsn(Opcodes.FLOAD, 1);
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "fullBrightnessFraction", "F");
            target.visitInsn(Opcodes.FCMPG);
            target.visitJumpInsn(Opcodes.IFGT, elseBranch);

            // brightness = 1.0f
            target.visitInsn(Opcodes.FCONST_1);
            target.visitVarInsn(Opcodes.FSTORE, 1);
            target.visitJumpInsn(Opcodes.GOTO, skipComplex);

            // else: brightness = 1.0 - (brightness - fbf) / (1.0 - fbf)
            target.visitLabel(elseBranch);
            target.visitInsn(Opcodes.FCONST_1);
            target.visitVarInsn(Opcodes.FLOAD, 1);
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "fullBrightnessFraction", "F");
            target.visitInsn(Opcodes.FSUB);
            target.visitInsn(Opcodes.FCONST_1);
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "fullBrightnessFraction", "F");
            target.visitInsn(Opcodes.FSUB);
            target.visitInsn(Opcodes.FDIV);
            target.visitInsn(Opcodes.FSUB);
            target.visitVarInsn(Opcodes.FSTORE, 1);

            target.visitLabel(skipComplex);

            // ── Step 4: Push 16 params and call addGenericTextureParticle ──

            // r = color.getRed()
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "color", COLOR_DESC);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COLOR_CLASS, "getRed", "()I", false);

            // g = color.getGreen()
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "color", COLOR_DESC);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COLOR_CLASS, "getGreen", "()I", false);

            // b = color.getBlue()
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "color", COLOR_DESC);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COLOR_CLASS, "getBlue", "()I", false);

            // a = (int)(color.getAlpha() * brightness)
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "color", COLOR_DESC);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COLOR_CLASS, "getAlpha", "()I", false);
            target.visitInsn(Opcodes.I2F);
            target.visitVarInsn(Opcodes.FLOAD, 1);
            target.visitInsn(Opcodes.FMUL);
            target.visitInsn(Opcodes.F2I);

            // blendSrc = this.src
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "src", "I");

            // blendDst = this.dst
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "dst", "I");

            // x = getX()
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PARTICLE_CLASS, "getX", "()F", false);

            // y = getY()
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PARTICLE_CLASS, "getY", "()F", false);

            // angle = getAngle()
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PARTICLE_CLASS, "getAngle", "()F", false);

            // offsetX
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "offsetX", "F");

            // offsetY
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "offsetY", "F");

            // width
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "width", "F");

            // height
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "height", "F");

            // tw
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "tw", "F");

            // th
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "th", "F");

            // renderCount
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "renderCount", "I");

            // ParticleBatchHelper.addGenericTextureParticle(...)
            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER,
                    "addGenericTextureParticle", ADD_DESC, false);

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
            // ParticleBatchHelper.flushGenericTextureBatch()
            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER, "flushGenericTextureBatch", "()V", false);

            // glDisable(GL_TEXTURE_2D = 3553)
            target.visitIntInsn(Opcodes.SIPUSH, 3553);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, GL11_OWNER, "glDisable", "(I)V", false);

            target.visitInsn(Opcodes.RETURN);
        }
    }
}
