package github.kasuminova.ssoptimizer.common.save;

import java.net.URL;
import java.security.CodeSource;

final class SaveClassArchive {
    private SaveClassArchive() {
    }

    static boolean sameAs(final Class<?> candidate, final Class<?> anchor) {
        if (candidate.getClassLoader() != anchor.getClassLoader() || anchor.getClassLoader() == null) {
            return false;
        }
        final String archive = archiveOf(anchor);
        return archive != null && archive.equals(archiveOf(candidate));
    }

    private static String archiveOf(final Class<?> type) {
        final CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return null;
        }
        final URL location = source.getLocation();
        if (location.getQuery() != null || location.getRef() != null) {
            return null;
        }
        final String archive;
        if (location.getProtocol().equals("jar")) {
            // LaunchWrapper 为每个类记录带 class 条目名的 jar URL。
            final String entry = "!/" + type.getName().replace('.', '/') + ".class";
            final String path = location.getPath();
            if (!path.endsWith(entry)) {
                return null;
            }
            archive = path.substring(0, path.length() - entry.length());
        } else {
            archive = location.toExternalForm();
        }
        return archive.startsWith("file:/") && archive.endsWith(".jar") && !archive.contains("!/") ? archive : null;
    }
}
