package github.kasuminova.ssoptimizer.asm.font;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 原版字体管理器方法级同步处理器。
 * <p>
 * 注入目标：{@code com.fs.graphics.font.BitmapFontManager}<br>
 * 注入动机：该类持有进程级静态可变状态——fonts 缓存表（普通 HashMap）与 .fnt
 * 解析分词器（lineTokens/tokenIndex）。游戏主线程、模组后台线程（如 BoxUtil
 * 逻辑线程）与 SSOptimizer 运行时字体生成路径会并发进入 {@code loadFont}，
 * 分词器串台会把字形度量/UV 解析成乱码并永久缓存（全模组环境下文本腐坏为
 * 方块的根因），并发 put/get 亦会腐坏 fonts 表结构。<br>
 * 注入效果：为 {@code getFont}/{@code loadFont} 两个 public static 入口追加
 * {@code ACC_SYNCHRONIZED}，全部调用方收敛到类对象锁，不改任何方法体逻辑。<br>
 * 为什么不用 Mixin：Mixin 模型无法在不整体覆写方法体的前提下为既有方法追加
 * synchronized 修饰（{@code @Overwrite} 需复制约百行 .fnt 解析逻辑，会随游戏
 * 版本漂移）；ACC_SYNCHRONIZED 是方法访问标志级修改，只有 ASM 能做到零逻辑
 * 侵入。
 */
public final class BitmapFontManagerSyncProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = GameClassNames.BITMAP_FONT_MANAGER;

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        final ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access,
                                             final String name,
                                             final String descriptor,
                                             final String signature,
                                             final String[] exceptions) {
                if (isSynchronizedEntry(access, name)) {
                    modified[0] = true;
                    return super.visitMethod(access | Opcodes.ACC_SYNCHRONIZED, name, descriptor, signature, exceptions);
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            private boolean isSynchronizedEntry(final int access, final String name) {
                if ((access & Opcodes.ACC_STATIC) == 0 || (access & Opcodes.ACC_SYNCHRONIZED) != 0) {
                    return false;
                }
                return GameMemberNames.BitmapFontManager.GET_FONT.equals(name)
                        || GameMemberNames.BitmapFontManager.LOAD_FONT.equals(name);
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}
