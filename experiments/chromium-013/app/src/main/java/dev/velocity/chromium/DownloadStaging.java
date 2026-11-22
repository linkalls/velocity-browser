package dev.velocity.chromium;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.regex.Pattern;

/** Identifies only files staged by the browser, never paths returned by a website. */
final class DownloadStaging {
    private static final String NORMAL = "velocity-normal-";
    private static final String PRIVATE = "velocity-private-";
    private static final String UUID_PATTERN = "[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}";
    private static final Pattern NORMAL_FILE = Pattern.compile(NORMAL + UUID_PATTERN + "-(.+)");
    private static final Pattern PRIVATE_FILE = Pattern.compile(PRIVATE + UUID_PATTERN + "-(.+)");
    private static final Pattern LEGACY_FILE = Pattern.compile("velocity-" + UUID_PATTERN + "-(.+)");
    private static final String PARTIAL_SUFFIX = ".crdownload";
    private static final Pattern ENGINE_PRIVATE_PARTIAL =
            Pattern.compile("Unconfirmed [0-9]+\\.crdownload");

    private DownloadStaging() {}

    static File target(File root, boolean privateMode, UUID id, String suggested) {
        return new File(root, (privateMode ? PRIVATE : NORMAL) + id + "-"
                + DownloadNamePolicy.filename(suggested));
    }

    static String normalFilename(File root, String enginePath) {
        if (enginePath == null || enginePath.isEmpty()) return null;
        File path = new File(enginePath);
        if (!path.isAbsolute()) return null;
        String name = path.getName();
        var m = NORMAL_FILE.matcher(name);
        if (!m.matches() || !m.group(1).equals(DownloadNamePolicy.filename(m.group(1)))) {
            return null;
        }
        try {
            File canonicalRoot = root.getCanonicalFile();
            if (!canonicalRoot.equals(path.getParentFile())
                    || !path.equals(path.getCanonicalFile())
                    || Files.isSymbolicLink(path.toPath())
                    || (path.exists() && !path.isFile())) {
                return null;
            }
            return m.group(1);
        } catch (IOException | SecurityException unavailable) {
            return null;
        }
    }

    static boolean isPrivateStagingFile(File file) {
        if (ENGINE_PRIVATE_PARTIAL.matcher(file.getName()).matches()) {
            return file.isFile() && !Files.isSymbolicLink(file.toPath());
        }
        return matchesStagingFile(PRIVATE_FILE, file);
    }

    static boolean isLegacyStagingFile(File file) {
        return matchesStagingFile(LEGACY_FILE, file);
    }

    private static boolean matchesStagingFile(Pattern pattern, File file) {
        String name = file.getName();
        if (name.endsWith(PARTIAL_SUFFIX)) {
            name = name.substring(0, name.length() - PARTIAL_SUFFIX.length());
        }
        var m = pattern.matcher(name);
        return m.matches() && m.group(1).equals(DownloadNamePolicy.filename(m.group(1)))
                && file.isFile() && !Files.isSymbolicLink(file.toPath());
    }
}
