package github.kasuminova.ssoptimizer.asm.render;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.objectweb.asm.*;

/**
 * BitmapFontRenderer 文本绘制处理器。
 * <p>
 * 注入目标：{@code com.fs.graphics.font.BitmapFontRenderer}<br>
 * 注入动机：原版逐字形渲染会产生大量 Java 侧 GL 调用，文本绘制路径在高 DPI 和大字号下开销明显；
 * 通过 ASM 替换关键方法体，可把单个字形输出折叠为 helper/native 调用。<br>
 * 注入效果：重写字形绘制与渲染入口，接入布局诊断、运行时字体缩放和批量渲染辅助逻辑。
 */
public final class EngineBitmapFontRendererProcessor implements AsmClassProcessor {
    static final String TARGET_CLASS                 = GameClassNames.BITMAP_FONT_RENDERER;
    static final String GLYPH_CLASS                  = GameClassNames.BITMAP_GLYPH;
    static final String FONT_CLASS                   = GameClassNames.BITMAP_FONT;
    static final String FONT_DESC                    = "L" + FONT_CLASS + ";";
    static final String ENTRY_METHOD                 = GameMemberNames.BitmapFontRenderer.RENDER;
    static final String ENTRY_DESC                   = "()V";
    static final String HELPER_OWNER                 =
            "github/kasuminova/ssoptimizer/common/render/engine/BitmapFontRendererHelper";
    static final String LAYOUT_HELPER_OWNER          =
            "github/kasuminova/ssoptimizer/common/render/engine/TextLayoutDiagnostics";
    static final String FONT_SWAP_HELPER_OWNER       =
            "github/kasuminova/ssoptimizer/common/font/RuntimeScaledFontCache";
    static final String TARGET_DESC                  = "(FFL" + GLYPH_CLASS + ";FZ)V";
    static final String HELPER_DESC                  = "(FFIIIFFFFFIF)V";
    static final String LAYOUT_HELPER_DESC           = "(IIIIFFII)V";
    static final String FONT_SWAP_HELPER_DESC        = "(Ljava/lang/Object;F)Ljava/lang/Object;";
    static final String FONT_SIZE_ADJUST_HELPER_DESC = "(Ljava/lang/Object;F)F";

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
                if (GameMemberNames.BitmapFontRenderer.DRAW_GLYPH.equals(name) && TARGET_DESC.equals(desc)) {
                    modified[0] = true;
                    return new RenderGlyphMethodReplacer(delegate);
                }
                if (ENTRY_METHOD.equals(name) && ENTRY_DESC.equals(desc)) {
                    modified[0] = true;
                    return new RenderEntryMethodAdapter(delegate);
                }
                return delegate;
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    static final class RenderGlyphMethodReplacer extends MethodVisitor {
        private final MethodVisitor target;

        RenderGlyphMethodReplacer(MethodVisitor target) {
            super(Opcodes.ASM9);
            this.target = target;
        }

        @Override
        public void visitCode() {
            target.visitCode();
            emitBody();
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            target.visitMaxs(0, 0);
        }

        @Override
        public void visitEnd() {
            target.visitEnd();
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return target.visitAnnotation(descriptor, visible);
        }

        private void emitBody() {
            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, GameMemberNames.BitmapGlyph.GET_GLYPH_ID, "()I", false);

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, GameMemberNames.BitmapGlyph.GET_X_OFFSET, "()I", false);

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, GameMemberNames.BitmapGlyph.GET_X_ADVANCE, "()I", false);

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, GameMemberNames.BitmapFontRenderer.FONT, FONT_DESC);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "identityHashCode", "(Ljava/lang/Object;)I", false);

            target.visitVarInsn(Opcodes.FLOAD, 4);

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, GameMemberNames.BitmapFontRenderer.REQUESTED_FONT_SIZE, "F");

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, GameMemberNames.BitmapFontRenderer.FONT, FONT_DESC);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FONT_CLASS, GameMemberNames.BitmapFont.GET_NOMINAL_FONT_SIZE, "()I", false);

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, GameMemberNames.BitmapFontRenderer.FONT, FONT_DESC);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FONT_CLASS, GameMemberNames.BitmapFont.GET_LINE_HEIGHT, "()I", false);

            target.visitMethodInsn(Opcodes.INVOKESTATIC, LAYOUT_HELPER_OWNER,
                    "recordGlyphLayout", LAYOUT_HELPER_DESC, false);

            target.visitVarInsn(Opcodes.FLOAD, 1);
            target.visitVarInsn(Opcodes.FLOAD, 2);

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, GameMemberNames.BitmapGlyph.GET_WIDTH, "()I", false);

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, GameMemberNames.BitmapGlyph.GET_HEIGHT, "()I", false);

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, GameMemberNames.BitmapGlyph.GET_BEARING_Y, "()I", false);

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, GameMemberNames.BitmapGlyph.GET_TEX_X, "()F", false);

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, GameMemberNames.BitmapGlyph.GET_TEX_Y, "()F", false);

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, GameMemberNames.BitmapGlyph.GET_TEX_WIDTH, "()F", false);

            target.visitVarInsn(Opcodes.ALOAD, 3);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GLYPH_CLASS, GameMemberNames.BitmapGlyph.GET_TEX_HEIGHT, "()F", false);

            target.visitVarInsn(Opcodes.FLOAD, 4);

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, GameMemberNames.BitmapFontRenderer.SHADOW_COPIES, "I");

            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, GameMemberNames.BitmapFontRenderer.SHADOW_SCALE, "F");

            target.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER,
                    "renderGlyphQuad", HELPER_DESC, false);
            target.visitInsn(Opcodes.RETURN);
        }
    }

    static final class RenderEntryMethodAdapter extends MethodVisitor {
        RenderEntryMethodAdapter(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            visitVarInsn(Opcodes.ALOAD, 0);
            visitVarInsn(Opcodes.ALOAD, 0);
            visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, GameMemberNames.BitmapFontRenderer.FONT, FONT_DESC);
            visitVarInsn(Opcodes.ALOAD, 0);
            visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, GameMemberNames.BitmapFontRenderer.REQUESTED_FONT_SIZE, "F");
            visitMethodInsn(Opcodes.INVOKESTATIC,
                    FONT_SWAP_HELPER_OWNER,
                    "resolveScaledFont",
                    FONT_SWAP_HELPER_DESC,
                    false);
            visitTypeInsn(Opcodes.CHECKCAST, FONT_CLASS);
            visitInsn(Opcodes.DUP_X1);
            visitFieldInsn(Opcodes.PUTFIELD, TARGET_CLASS, GameMemberNames.BitmapFontRenderer.FONT, FONT_DESC);

            visitVarInsn(Opcodes.ALOAD, 0);
            visitInsn(Opcodes.SWAP);
            visitVarInsn(Opcodes.ALOAD, 0);
            visitFieldInsn(Opcodes.GETFIELD, TARGET_CLASS, GameMemberNames.BitmapFontRenderer.REQUESTED_FONT_SIZE, "F");
            visitMethodInsn(Opcodes.INVOKESTATIC,
                    FONT_SWAP_HELPER_OWNER,
                    "adjustRequestedFontSize",
                    FONT_SIZE_ADJUST_HELPER_DESC,
                    false);
            visitFieldInsn(Opcodes.PUTFIELD, TARGET_CLASS, GameMemberNames.BitmapFontRenderer.REQUESTED_FONT_SIZE, "F");
        }
    }
}
