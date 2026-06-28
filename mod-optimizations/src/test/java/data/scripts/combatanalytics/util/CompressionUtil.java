package data.scripts.combatanalytics.util;

/**
 * 测试夹具：模拟 DCR 的 {@code data.scripts.combatanalytics.util.CompressionUtil} 形状
 * （内部名、目标方法签名与 try/catch 体），供 {@code DcrCompressionProcessor} 处理。
 * <p>
 * compress/decompress 体为「哨兵」实现，便于断言处理器已将其改写为委派 helper；
 * checksum 为带分支/循环的方法，用于验证处理器保留无关方法（及其栈帧）不变。
 */
public class CompressionUtil {

    public static byte[] compress(final String text) {
        try {
            return new byte[]{(byte) 'X'};
        } catch (final RuntimeException e) {
            throw new RuntimeException(e);
        }
    }

    public static String decompress(final byte[] bytes) {
        try {
            return "ORIGINAL";
        } catch (final RuntimeException e) {
            throw new RuntimeException(e);
        }
    }

    public static int checksum(final byte[] bytes) {
        int sum = 0;
        for (int i = 0; i < bytes.length; i++) {
            sum += bytes[i] & 0xFF;
        }
        return sum;
    }
}
