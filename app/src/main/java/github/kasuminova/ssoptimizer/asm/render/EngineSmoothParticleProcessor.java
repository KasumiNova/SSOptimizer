package github.kasuminova.ssoptimizer.asm.render;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.objectweb.asm.*;

/**
 * Rewrites {@code SmoothParticle.preBatch()}, {@code render()}, and
 * {@code postBatch()} to use {@link ParticleBatchHelper} for delayed batching
 * while preserving immediate-mode submission semantics at flush time.
 * <p>
 * Original flow: preBatch opens glBegin(GL_QUADS), each render() emits
 * 9 GL/JNI calls (color + 4×tex + 4×vert), postBatch closes glEnd.
 * <p>
 * New flow: preBatch sets up GL state + beginBatch(), each render() buffers
 * vertex data in Java (zero GL calls), postBatch flushes them through one
 * helper/native call that emits the buffered quad stream in immediate mode.
 */
public final class EngineSmoothParticleProcessor implements AsmClassProcessor {
    static final String TARGET_CLASS = GameClassNames.SMOOTH_PARTICLE;

    // Helper for batched particle rendering
    static final String HELPER_OWNER =
            "github/kasuminova/ssoptimizer/common/render/engine/ParticleBatchHelper";

    // Game class references
    private static final String PARTICLE_CLASS      = TARGET_CLASS;
    private static final String BASE_PARTICLE_CLASS = GameClassNames.BASE_PARTICLE;
    private static final String TEXTURE_CLASS       = GameClassNames.TEXTURE_OBJECT;
    private static final String TEXTURE_DESC        = "L" + TEXTURE_CLASS + ";";
    private static final String COLOR_CLASS         = "java/awt/Color";
    private static final String COLOR_DESC          = "L" + COLOR_CLASS + ";";
    private static final String GL11_OWNER          = "org/lwjgl/opengl/GL11";

    private static final String BIND_METHOD = "bind";

    // RenderStateUtils.adjustBrightness(Color, float) — brightness-adjusts all RGBA components
    private static final String COLOR_UTIL_CLASS    = GameClassNames.RENDER_STATE_UTILS;
    private static final String COLOR_ADJUST_METHOD = GameMemberNames.RenderStateUtils.ADJUST_BRIGHTNESS;

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

            // glBlendFunc(GL_SRC_ALPHA = 770, GL_ONE = 1)
            target.visitIntInsn(Opcodes.SIPUSH, 770);
            target.visitInsn(Opcodes.ICONST_1);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, GL11_OWNER, "glBlendFunc", "(II)V", false);

            // Texture bind: if (this.override != null) override.bind() else texture.bind()
            Label bindStatic = new Label();
            Label afterBind = new Label();

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "override", TEXTURE_DESC);
            target.visitJumpInsn(Opcodes.IFNULL, bindStatic);

            // override.bind()
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "override", TEXTURE_DESC);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TEXTURE_CLASS, BIND_METHOD, "()V", false);
            target.visitJumpInsn(Opcodes.GOTO, afterBind);

            // texture.bind() (static field)
            target.visitLabel(bindStatic);
            target.visitFieldInsn(Opcodes.GETSTATIC, PARTICLE_CLASS, "texture", TEXTURE_DESC);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TEXTURE_CLASS, BIND_METHOD, "()V", false);

            target.visitLabel(afterBind);

            // ParticleBatchHelper.beginSmoothBatch() — delayed collection, flushed
            // via native/Java immediate mode instead of client-array glDrawArrays.
            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER, "beginSmoothBatch", "()V", false);

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
            Label returnLabel = new Label();
            Label checkBrightnessMult = new Label();

            // Brightness check: if (getBrightnessOverride() == 0.0f) return;
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PARTICLE_CLASS,
                    "getBrightnessOverride", "()F", false);
            target.visitInsn(Opcodes.FCONST_0);
            target.visitInsn(Opcodes.FCMPL);
            target.visitJumpInsn(Opcodes.IFEQ, returnLabel);

            // if (getBrightnessMult() == 0.0f) return;
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PARTICLE_CLASS,
                    "getBrightnessMult", "()F", false);
            target.visitInsn(Opcodes.FCONST_0);
            target.visitInsn(Opcodes.FCMPL);
            target.visitJumpInsn(Opcodes.IFNE, checkBrightnessMult);
            target.visitInsn(Opcodes.RETURN);

            target.visitLabel(checkBrightnessMult);

            // Color adjustedColor = RenderStateUtils.adjustBrightness(this.color, getBrightness());
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "color", COLOR_DESC);
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PARTICLE_CLASS,
                    "getBrightness", "()F", false);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, COLOR_UTIL_CLASS,
                    COLOR_ADJUST_METHOD, "(Ljava/awt/Color;F)Ljava/awt/Color;", false);
            target.visitVarInsn(Opcodes.ASTORE, 1); // local 1 = adjustedColor

            // r = adjustedColor.getRed()
            target.visitVarInsn(Opcodes.ALOAD, 1);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COLOR_CLASS, "getRed", "()I", false);

            // g = adjustedColor.getGreen()
            target.visitVarInsn(Opcodes.ALOAD, 1);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COLOR_CLASS, "getGreen", "()I", false);

            // b = adjustedColor.getBlue()
            target.visitVarInsn(Opcodes.ALOAD, 1);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COLOR_CLASS, "getBlue", "()I", false);

            // a = adjustedColor.getAlpha()
            target.visitVarInsn(Opcodes.ALOAD, 1);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COLOR_CLASS, "getAlpha", "()I", false);

            // getX()
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PARTICLE_CLASS, "getX", "()F", false);

            // getY()
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PARTICLE_CLASS, "getY", "()F", false);

            // this.offsetX
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "offsetX", "F");

            // this.offsetY
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "offsetY", "F");

            // this.size
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "size", "F");

            // ParticleBatchHelper.addSmoothParticle(r, g, b, a, x, y, offsetX, offsetY, size)
            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER,
                    "addSmoothParticle", "(IIIIFFFFF)V", false);

            target.visitLabel(returnLabel);
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
            // Match original buffered postBatch path: re-assert GL state immediately
            // before drawing, in case any other renderer changed it between preBatch
            // and postBatch.
            target.visitIntInsn(Opcodes.SIPUSH, 3553);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, GL11_OWNER, "glEnable", "(I)V", false);

            target.visitIntInsn(Opcodes.SIPUSH, 3042);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, GL11_OWNER, "glEnable", "(I)V", false);

            target.visitIntInsn(Opcodes.SIPUSH, 770);
            target.visitInsn(Opcodes.ICONST_1);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, GL11_OWNER, "glBlendFunc", "(II)V", false);

            Label bindStatic = new Label();
            Label afterBind = new Label();

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "override", TEXTURE_DESC);
            target.visitJumpInsn(Opcodes.IFNULL, bindStatic);

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, PARTICLE_CLASS, "override", TEXTURE_DESC);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TEXTURE_CLASS, BIND_METHOD, "()V", false);
            target.visitJumpInsn(Opcodes.GOTO, afterBind);

            target.visitLabel(bindStatic);
            target.visitFieldInsn(Opcodes.GETSTATIC, PARTICLE_CLASS, "texture", TEXTURE_DESC);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TEXTURE_CLASS, BIND_METHOD, "()V", false);

            target.visitLabel(afterBind);

            // ParticleBatchHelper.flushSmoothBatch()
            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER, "flushSmoothBatch", "()V", false);

            // glDisable(GL_TEXTURE_2D = 3553)
            target.visitIntInsn(Opcodes.SIPUSH, 3553);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, GL11_OWNER, "glDisable", "(I)V", false);

            target.visitInsn(Opcodes.RETURN);
        }
    }
}
