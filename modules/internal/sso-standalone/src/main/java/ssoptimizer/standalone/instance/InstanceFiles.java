package ssoptimizer.standalone.instance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

final class InstanceFiles {
    static final LinkOption NO_LINK = LinkOption.NOFOLLOW_LINKS;

    private InstanceFiles() {}

    static Path prospective(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.exists(absolute, NO_LINK)) return absolute.toRealPath();
        return prospective(absolute.getParent()).resolve(absolute.getFileName());
    }

    static void separate(Path first, Path second) throws IOException {
        if (first.startsWith(second) || second.startsWith(first)) {
            throw new IOException("目录不得重叠：" + first + " 与 " + second);
        }
    }

    static Path child(Path root, String relative) throws IOException {
        Path path = Path.of(relative);
        if (path.isAbsolute() || relative.isEmpty() || !path.normalize().equals(path)
                || path.startsWith("..") || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0) {
            throw new IOException("无效的实例相对路径：" + relative);
        }
        Path result = root.resolve(path);
        if (result.equals(root) || !result.normalize().startsWith(root) || !prospective(result).equals(result)) {
            throw new IOException("实例写入路径含目录链接或越界：" + result);
        }
        return result;
    }

    static Path directory(Path root, String relative) throws IOException {
        return Files.createDirectories(child(root, relative));
    }

    static void regular(Path file) throws IOException {
        if (!Files.isRegularFile(file, NO_LINK)) throw new IOException("需要普通文件：" + file);
    }

    static void copyOwned(Path source, Path target, java.nio.file.CopyOption... options) throws IOException {
        Files.copy(source, target, options);
        var posix = Files.getFileAttributeView(target, java.nio.file.attribute.PosixFileAttributeView.class, NO_LINK);
        if (posix != null) {
            var permissions = new java.util.HashSet<>(posix.readAttributes().permissions());
            if (permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)) posix.setPermissions(permissions);
        } else {
            var dos = Files.getFileAttributeView(target, java.nio.file.attribute.DosFileAttributeView.class, NO_LINK);
            if (dos != null && dos.readAttributes().isReadOnly()) dos.setReadOnly(false);
        }
    }

    static String digest(Path file) throws IOException {
        MessageDigest hash;
        try {
            hash = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), hash)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    static void deletePrivateTree(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }

}
