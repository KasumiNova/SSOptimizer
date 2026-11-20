package github.kasuminova.ssoptimizer.standalone.obf;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class Digests {
    private Digests() {
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

    static String bytes(final byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }
}
