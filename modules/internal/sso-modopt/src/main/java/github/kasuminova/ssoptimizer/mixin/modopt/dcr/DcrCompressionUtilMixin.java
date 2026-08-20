package github.kasuminova.ssoptimizer.mixin.modopt.dcr;

import github.kasuminova.ssoptimizer.modopt.dcr.DcrCompressionHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * DCR 压缩内核替换的 Mixin 重写。
 * <p>
 * 注入目标：{@code data.scripts.combatanalytics.util.CompressionUtil}（外部模组 DCR 的类）<br>
 * 注入动机：DCR 读档/存档热点的压缩段为 {@code Deflater} 级别 9（最慢档）；在 {@code byte[]}
 * 边界替换内核为 {@link DcrCompressionHelper}（Zstd 写 + Deflate 读回退），DCR 自身（非标准）
 * 的 Base64 层原封不动地包在外侧。<br>
 * 注入效果：{@code compress(String)} 与 {@code decompress(byte[])} 方法体整体替换为对 helper
 * 同名方法的委派。
 */
@Mixin(targets = "data.scripts.combatanalytics.util.CompressionUtil")
public abstract class DcrCompressionUtilMixin {

    /**
     * 压缩内核替换为 Zstd 写。
     *
     * @param text 待压缩文本（DCR 战报 XML）
     * @return Zstd 帧字节
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 compress(String) 方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    public static byte[] compress(final String text) {
        return DcrCompressionHelper.compress(text);
    }

    /**
     * 解压内核替换为「Zstd 读 + 旧存档 Deflate 回退」。
     *
     * @param bytes 压缩字节（已由 DCR 的 Base64 层解码而来）
     * @return 原始 UTF-8 文本
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 decompress(byte[]) 方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    public static String decompress(final byte[] bytes) {
        return DcrCompressionHelper.decompress(bytes);
    }
}
